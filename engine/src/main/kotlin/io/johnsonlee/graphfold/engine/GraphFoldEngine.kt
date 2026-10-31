package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.GitWorkspace
import io.johnsonlee.graphfold.engine.git.GitWorkspaceManager
import io.johnsonlee.graphfold.engine.git.WorkspaceLeaseTimeoutException
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.graphite.RuleAuditUpdate
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphfold.engine.origin.bindOrigins
import io.johnsonlee.graphfold.engine.origin.prepareOrigins
import io.johnsonlee.graphfold.engine.source.DiffLocations
import io.johnsonlee.graphfold.engine.source.ChangeClassifier
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphfold.engine.source.IndexedSource
import io.johnsonlee.graphfold.engine.source.JavaSourceIndex
import io.johnsonlee.graphfold.engine.source.KotlinSourceIndex
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.ProofPlanner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Public synchronous SDK. Each call owns its workspace and analysis state. */
class GraphFoldEngine(private val configuration: EngineConfiguration) {
    // The SDK boundary must return a blocking diagnostic for any failed adapter, never a partial PASS.
    @Suppress("TooGenericExceptionCaught")
    fun analyze(request: AnalysisRequest): AnalysisResult {
        val state = RunState(request)
        return try {
            val scenarios = FoldDocument.parse(request.fold, configuration.maximumScenarios)
            state.initialize(scenarios)
            state.stage = AnalysisStage.GIT
            val manager = GitWorkspaceManager(
                configuration.cacheDirectory.resolve("git"),
                configuration.maximumWorkspaceSlots,
                configuration.workspaceLeaseTimeout,
            )
            manager.open(request.repository, request.base, request.head).use { workspace ->
                state.baseSha = workspace.baseSha
                state.headSha = workspace.headSha
                execute(workspace, scenarios, state)
            }
            state.result()
        } catch (failure: WorkspaceLeaseTimeoutException) {
            state.diagnostics += Diagnostic(AnalysisStage.GIT, "WORKSPACE_CAPACITY_TIMEOUT", requireNotNull(failure.message))
            state.result()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            state.diagnostics += Diagnostic(state.stage, "ANALYSIS_CANCELLED", "Analysis was interrupted")
            state.result()
        } catch (failure: Exception) {
            state.diagnostics += Diagnostic(state.stage, "ANALYSIS_FAILED", failure.message ?: failure.javaClass.simpleName)
            state.result()
        }
    }

    private fun execute(workspace: GitWorkspace, scenarios: List<FoldScenario>, state: RunState) {
        val changes = workspace.changes()
        state.obligations = changes.size
        if (changes.isEmpty()) {
            state.ruleAudits.replaceAll { it.copy(message = "No base/head diff; graph selection was not executed") }
            return
        }
        state.stage = AnalysisStage.BUILD
        val run = Files.createTempDirectory(configuration.cacheDirectory, "analysis-")
        try {
            val compiler = ProjectCompiler(configuration.buildTimeout, configuration.buildEnvironmentAllowlist)
            val base = compiler.compile(workspace.baseDirectory, run.resolve("base"))
            val head = compiler.compile(workspace.headDirectory, run.resolve("head"))
            state.stage = AnalysisStage.SOURCE
            val origins = prepareOrigins(base, head, workspace.baseDirectory, workspace.headDirectory, changes, run.resolve("normalized"))
            origins.diagnostics.forEach { state.diagnostics += Diagnostic(AnalysisStage.SOURCE, "UNVERIFIED_ORIGIN", it) }
            if (origins.diagnostics.isNotEmpty()) return
            val oldProject = requireNotNull(origins.base)
            val newProject = requireNotNull(origins.head)
            val obligations = changes.flatMap { change -> obligations(workspace, change, oldProject, newProject, state) }
            state.obligations = obligations.size
            val passed = BooleanArray(obligations.size) { true }
            scenarios.forEach { scenario ->
                val comparison = compareScenario(oldProject, newProject, scenario, state)
                obligations.forEachIndexed { index, obligation ->
                    if (!checkObligation(obligation, comparison, scenario.name, state)) passed[index] = false
                }
            }
            state.proved = passed.count { it }
        } finally {
            cleanupDirectory(run)
        }
    }

    private fun compareScenario(base: OriginProject, head: OriginProject, scenario: FoldScenario, state: RunState): SnapshotComparison {
        state.stage = AnalysisStage.GRAPH
        val old = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.BASE, index, update) }
            .analyzeOriginProject(base, scenario.rules)
        val new = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.HEAD, index, update) }
            .analyzeOriginProject(head, scenario.rules)
        (old.diagnostics.map { "base: $it" } + new.diagnostics.map { "head: $it" }).forEach {
            state.diagnostics += Diagnostic(AnalysisStage.GRAPH, "INCOMPLETE_GRAPH", it)
        }
        scenario.rules.indices.forEach { index ->
            if (old.matchedRules[index] == 0 && new.matchedRules[index] == 0) {
                state.diagnostics += Diagnostic(
                    AnalysisStage.FOLD, "UNMATCHED_RULE", "${scenario.name}: rule $index matched neither revision",
                )
            }
        }
        state.stage = AnalysisStage.COMPARE
        return SnapshotComparison(old, new)
    }

    private fun obligations(
        workspace: GitWorkspace,
        change: ChangedFile,
        base: OriginProject,
        head: OriginProject,
        state: RunState,
    ): List<ProofObligation> {
        if (listOfNotNull(change.basePath, change.headPath).any { !it.endsWith(".java") && !it.endsWith(".kt") } || change.binary) {
            state.diagnostics += Diagnostic(
                AnalysisStage.SOURCE, "UNMAPPED_CHANGE", "Unsupported changed input: ${change.headPath ?: change.basePath}",
            )
            return emptyList()
        }
        val old = source(workspace.baseDirectory, change.basePath, Revision.BASE, base)
        val new = source(workspace.headDirectory, change.headPath, Revision.HEAD, head)
        (old?.diagnostics.orEmpty() + new?.diagnostics.orEmpty()).forEach {
            state.diagnostics += Diagnostic(AnalysisStage.SOURCE, "SOURCE_RESOLUTION_FAILED", it)
        }
        val planned = planChanges(old, new, change)
        if (planned.isEmpty()) {
            state.diagnostics += Diagnostic(
                AnalysisStage.SOURCE, "UNMAPPED_CHANGE", "Cannot bind ${change.headPath ?: change.basePath} to a declaration",
            )
        }
        return planned
    }

    private fun planChanges(old: IndexedSource?, new: IndexedSource?, change: ChangedFile): List<ProofObligation> {
        val locations = DiffLocations.locations(change, old?.text, new?.text)
        val oldObligations = old?.let { ProofPlanner.plan(it, locations.filter { loc -> loc.revision == Revision.BASE }) }.orEmpty()
        val newObligations = new?.let { ProofPlanner.plan(it, locations.filter { loc -> loc.revision == Revision.HEAD }) }.orEmpty()
        val moved = change.basePath != null && change.headPath != null && change.basePath != change.headPath
        val moveKinds = if (moved) setOf(
            if (Path.of(change.basePath).fileName == Path.of(change.headPath).fileName) ChangeKind.FILE_MOVED else ChangeKind.FILE_RENAMED,
        ) else emptySet()
        val moveObligations = if (moved && locations.isEmpty()) {
            (old?.symbols.orEmpty() + new?.symbols.orEmpty()).filter { it.kind == SymbolKind.CLASS }
                .map { ProofObligation(it.identity, it.kind, it.owner, emptyList()) }
        } else emptyList()
        return (oldObligations + newObligations + moveObligations).groupBy { it.identity }.values.map { group ->
            val fragments = group.flatMap { it.locations }.distinct()
            group.first().copy(
                locations = fragments, changes = ChangeClassifier.classify(old, new, fragments) + moveKinds,
                basePath = change.basePath, headPath = change.headPath,
            )
        }
    }

    private fun source(root: Path, path: String?, revision: Revision, project: OriginProject): IndexedSource? =
        when {
            path == null -> null
            path.endsWith(".kt") -> KotlinSourceIndex(project.sourceClasses, project.compiled.sourceOutputs)
                .use { it.index(root, path, revision) }
            path.endsWith(".java") -> JavaSourceIndex(project.compiled.sourceRoots, project.compiled.dependencies)
                .use { it.index(root, path, revision) }
            else -> null
        }?.let { bindOrigins(it, revision, project.catalog, project.owners) }

    private fun checkObligation(
        obligation: ProofObligation, comparison: SnapshotComparison, scenario: String, state: RunState,
    ): Boolean {
        val differences = comparison.differences(obligation)
        val proved = differences.isEmpty()
        val reason = when {
            !proved -> ReasonCode.SURVIVING_DIFFERENCE
            comparison.isUnreachable(obligation) -> ReasonCode.UNREACHABLE
            else -> ReasonCode.FOLDED_EQUIVALENT
        }
        state.reasons += VerdictReason(
            obligation.changes, reason, reasonMessage(reason, differences),
            obligation.locations, scenario, obligation.basePath, obligation.headPath,
        )
        if (!proved && obligation.locations.isEmpty()) {
            state.diagnostics += Diagnostic(AnalysisStage.COMPARE, "UNLOCATED_MOVE_DIFFERENCE",
                "Moving ${obligation.basePath} to ${obligation.headPath} changes JVM representation: ${differences.joinToString()}")
        } else if (!proved) {
            val message = "Surviving program representation differs: ${differences.joinToString()}"
            state.findings += Finding(
                digest("$scenario|${obligation.locations}|$message"), FindingKind.UNVERIFIABLE_CHANGE,
                "folded-program-equivalence", message, obligation.locations,
                differences.map { Evidence("Remaining difference in $it") }, listOf(scenario),
            )
        }
        return proved
    }

    private fun reasonMessage(reason: ReasonCode, differences: List<String>): String = when (reason) {
        ReasonCode.UNREACHABLE -> "No surviving execution path reaches this change under the analyzed entry model"
        ReasonCode.FOLDED_EQUIVALENT -> "No surviving difference in the analyzed representation"
        else -> "Surviving differences: ${differences.joinToString()}"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private class RunState(request: AnalysisRequest) {
    var stage = AnalysisStage.INPUT
    var baseSha = request.base
    var headSha = request.head
    var scenarios = emptyList<String>()
    var obligations = 0
    var proved = 0
    val reasons = mutableListOf<VerdictReason>()
    val findings = mutableListOf<Finding>()
    val diagnostics = mutableListOf<Diagnostic>()
    val ruleAudits = mutableListOf<RuleAudit>()

    fun initialize(folds: List<FoldScenario>) {
        scenarios = folds.map { it.name }
        folds.forEach { scenario ->
            Revision.entries.forEach { revision -> ruleAudits.addAll(initialAudits(scenario, revision)) }
        }
    }

    fun audit(scenario: String, revision: Revision, ruleIndex: Int, update: RuleAuditUpdate) {
        val index = ruleAudits.indexOfFirst { it.scenario == scenario && it.revision == revision && it.ruleIndex == ruleIndex }
        ruleAudits[index] = ruleAudits[index].copy(selectionStatus = update.selectionStatus,
            selectedNodes = update.selectedNodes, matchedCallCount = update.matchedCallCount, message = update.message)
    }
    fun result() = AnalysisResult(
        findings.toList(), diagnostics.toList(), AnalysisMetadata(baseSha, headSha, scenarios, obligations, proved),
        reasons.toList(), ruleAudits.toList(),
    )
}

private fun initialAudits(scenario: FoldScenario, revision: Revision): List<RuleAudit> = scenario.rules.mapIndexed { index, rule ->
    val selector = when (rule) {
        is FoldSites -> RuleSelector(RuleSelectorKind.CYPHER, rule.select, rule.description)
        is ConstantFold -> RuleSelector(RuleSelectorKind.CALL_SITE_MATCH, null, rule.description)
    }
    RuleAudit(scenario.name, revision, index, selector, message = "Analysis did not reach rule selection")
}
