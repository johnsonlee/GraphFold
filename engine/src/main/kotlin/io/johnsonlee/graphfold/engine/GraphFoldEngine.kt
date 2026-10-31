package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.CompiledProject
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
import io.johnsonlee.graphfold.engine.origin.readOrigins
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

/**
 * Synchronous entry point for comparing Git revisions under caller-supplied folding assumptions.
 * Each call owns its analysis state and acquires an exclusive lease on a reusable workspace.
 * Analysis may execute repository build scripts; the caller must trust or isolate the analyzed repository.
 *
 * @param configuration Cache locations, resource limits, and inherited build environment policy.
 */
class GraphFoldEngine(private val configuration: EngineConfiguration) {
    /**
     * Resolves and builds [request]'s revisions, then checks diff obligations in every folding scenario.
     *
     * Adapter exceptions and analysis gaps become blocking diagnostics rather than a partial passing result.
     * Interruption produces an analysis-cancelled diagnostic and restores the thread's interrupt flag.
     * Workspace leases are released before returning; persistent checkout and build state remain reusable.
     *
     * @return Complete or partial report whose verdict fails for any finding, blocking diagnostic, or unproved obligation.
     */
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
            state.diagnostics += Diagnostic(AnalysisStage.GIT, DiagnosticCode.WORKSPACE_CAPACITY_TIMEOUT, requireNotNull(failure.message))
            state.result()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            state.diagnostics += Diagnostic(state.stage, DiagnosticCode.ANALYSIS_CANCELLED, "Analysis was interrupted")
            state.result()
        } catch (failure: Exception) {
            state.diagnostics += Diagnostic(state.stage, DiagnosticCode.ANALYSIS_FAILED, failure.message ?: failure.javaClass.simpleName)
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
            val inputChanges = changes.filterNot { it.isJvmSourceChange() }
            val inputProof = BuildInputProof(inputChanges, base.production, head.production)
            if (prepareBuildInputProof(inputProof, changes.size, scenarios, state)) {
                analyzeSources(workspace, changes, scenarios, state, CompiledRevisions(base, head), run, inputProof)
            }
        } finally {
            cleanupDirectory(run)
        }
    }

    private fun analyzeSources(
        workspace: GitWorkspace, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions, run: Path, inputProof: BuildInputProof,
    ) {
        state.stage = AnalysisStage.SOURCE
        if (prepareInstrumentedInputProof(inputProof, changes.size, compiled, workspace, scenarios, state)) return
        val origins = prepareOrigins(
            compiled.base, compiled.head, workspace.baseDirectory, workspace.headDirectory, changes, run.resolve("normalized"),
        )
        origins.diagnostics.forEach { state.diagnostics += Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.UNVERIFIED_ORIGIN, it) }
        if (origins.diagnostics.isNotEmpty()) return
        val oldProject = requireNotNull(origins.base)
        val newProject = requireNotNull(origins.head)
        val obligations = changes.filter { it.isJvmSourceChange() }
            .flatMap { change -> obligations(workspace, change, oldProject, newProject, state) }
        state.obligations = obligations.size + inputProof.changes.size
        val passed = BooleanArray(obligations.size) { true }
        var inputsProved = true
        scenarios.forEach { scenario ->
            val comparison = compareScenario(oldProject, newProject, scenario, state)
            val inputPassed = checkBuildInputs(inputProof, comparison, scenario.name, state)
            inputsProved = inputsProved && inputPassed
            obligations.forEachIndexed { index, obligation ->
                if (!checkObligation(obligation, comparison, scenario.name, state)) passed[index] = false
            }
        }
        state.proved = passed.count { it } + if (inputsProved) inputProof.changes.size else 0
    }

    private fun prepareInstrumentedInputProof(
        proof: BuildInputProof, changeCount: Int, compiled: CompiledRevisions, workspace: GitWorkspace,
        scenarios: List<FoldScenario>, state: RunState,
    ): Boolean {
        if (proof.changes.size != changeCount) return false
        val failures = readOrigins(compiled.base, workspace.baseDirectory).diagnostics.map { "base: $it" } +
            readOrigins(compiled.head, workspace.headDirectory).diagnostics.map { "head: $it" }
        return when {
            failures.isNotEmpty() -> {
                failures.forEach { state.diagnostics += Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.UNVERIFIED_ORIGIN, it) }
                true
            }
            proof.unchangedClasses(compiled.base.classes, compiled.head.classes) -> {
                recordUnchangedInputs(proof, scenarios, state,
                    "Production outputs are unchanged after validating and removing GraphFold compiler-origin instrumentation")
                true
            }
            else -> false
        }
    }

    private fun prepareBuildInputProof(
        proof: BuildInputProof, changeCount: Int, scenarios: List<FoldScenario>, state: RunState,
    ): Boolean {
        if (proof.changes.isEmpty()) return true
        val gap = proof.gap()
        return when {
            gap != null -> {
                state.diagnostics += gap
                scenarios.forEach { state.reasons += proof.reasons(ReasonCode.ANALYSIS_INCOMPLETE, it.name, gap.message) }
                false
            }
            proof.changes.size == changeCount && proof.unchanged -> {
                recordUnchangedInputs(proof, scenarios, state,
                    "Actual production bytecode, resources, runtime dependencies and package contents are unchanged")
                false
            }
            else -> true
        }
    }

    private fun recordUnchangedInputs(proof: BuildInputProof, scenarios: List<FoldScenario>, state: RunState, message: String) {
        state.proved = proof.changes.size
        scenarios.forEach { state.reasons += proof.reasons(ReasonCode.BUILD_OUTPUT_UNCHANGED, it.name, message) }
        state.ruleAudits.replaceAll { it.copy(message = "Production outputs are unchanged; graph selection was not executed") }
    }

    private fun checkBuildInputs(
        proof: BuildInputProof, comparison: SnapshotComparison, scenario: String, state: RunState,
    ): Boolean {
        if (proof.changes.isEmpty()) return true
        val differences = comparison.wholeProgramDifferences()
        val proved = differences.isEmpty()
        val message = if (proved) "Production resources and runtime dependencies are unchanged; " +
            "the entire analyzed production program is equivalent after folding"
        else "Folded production program differs; non-JVM input effects cannot be independently attributed: " +
            differences.joinToString()
        state.reasons += proof.reasons(
            if (proved) ReasonCode.FOLDED_EQUIVALENT else ReasonCode.ANALYSIS_INCOMPLETE, scenario, message,
        )
        if (!proved) state.diagnostics += Diagnostic(AnalysisStage.COMPARE, DiagnosticCode.UNVERIFIED_BUILD_INPUT, message)
        return proved
    }

    private fun compareScenario(base: OriginProject, head: OriginProject, scenario: FoldScenario, state: RunState): SnapshotComparison {
        state.stage = AnalysisStage.GRAPH
        val old = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.BASE, index, update) }
            .analyzeOriginProject(base, scenario.rules)
        val new = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.HEAD, index, update) }
            .analyzeOriginProject(head, scenario.rules)
        (old.diagnostics.map { "base: $it" } + new.diagnostics.map { "head: $it" }).forEach {
            state.diagnostics += Diagnostic(AnalysisStage.GRAPH, DiagnosticCode.INCOMPLETE_GRAPH, it)
        }
        scenario.rules.indices.forEach { index ->
            if (old.matchedRules[index] == 0 && new.matchedRules[index] == 0) {
                state.diagnostics += Diagnostic(
                    AnalysisStage.FOLD, DiagnosticCode.UNMATCHED_RULE, "${scenario.name}: rule $index matched neither revision",
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
        if (change.binary) {
            state.diagnostics += Diagnostic(
                AnalysisStage.SOURCE, DiagnosticCode.UNMAPPED_CHANGE, "Unsupported changed input: ${change.headPath ?: change.basePath}",
            )
            return emptyList()
        }
        val old = source(workspace.baseDirectory, change.basePath, Revision.BASE, base)
        val new = source(workspace.headDirectory, change.headPath, Revision.HEAD, head)
        (old?.diagnostics.orEmpty() + new?.diagnostics.orEmpty()).forEach {
            state.diagnostics += Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.SOURCE_RESOLUTION_FAILED, it)
        }
        val planned = planChanges(old, new, change)
        if (planned.isEmpty()) {
            state.diagnostics += Diagnostic(
                AnalysisStage.SOURCE, DiagnosticCode.UNMAPPED_CHANGE, "Cannot bind ${change.headPath ?: change.basePath} to a declaration",
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
            state.diagnostics += Diagnostic(AnalysisStage.COMPARE, DiagnosticCode.UNLOCATED_MOVE_DIFFERENCE,
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

private data class CompiledRevisions(val base: CompiledProject, val head: CompiledProject)

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
