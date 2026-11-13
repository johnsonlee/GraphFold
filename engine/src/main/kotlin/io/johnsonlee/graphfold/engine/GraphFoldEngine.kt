package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.graphite.RuleAuditUpdate
import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphfold.engine.origin.bindOrigins
import io.johnsonlee.graphfold.engine.origin.hasNoProvenanceClaim
import io.johnsonlee.graphfold.engine.origin.prepareOrigins
import io.johnsonlee.graphfold.engine.origin.readOrigins
import io.johnsonlee.graphfold.engine.origin.sourceIndexClasses
import io.johnsonlee.graphfold.engine.source.DiffLocations
import io.johnsonlee.graphfold.engine.source.ChangeClassifier
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphfold.engine.source.IndexedSource
import io.johnsonlee.graphfold.engine.source.JavaSourceIndex
import io.johnsonlee.graphfold.engine.source.javaCompilerInputs
import io.johnsonlee.graphfold.engine.source.KotlinSourceIndex
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.ProofPlanner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Static control-coverage analysis over prepared bytecode, engine-owned Git differences and optional source evidence. */
class GraphFoldEngine(private val configuration: EngineConfiguration) {
    /** Computes the comparison from committed Git trees; integrations cannot supply or omit changed paths. */
    fun prepare(
        base: PreparedRevision,
        head: PreparedRevision, fold: String,
    ): PreparedComparison = prepareComparison(base, head, fold)

    /** Analyzes an engine-owned comparison prepared from Git. */
    fun analyze(comparison: PreparedComparison): AnalysisResult = analyze(comparison.request)

    /** Computes Git changes and analyzes the two independently prepared revisions. */
    fun analyze(
        base: PreparedRevision,
        head: PreparedRevision, fold: String,
    ): AnalysisResult = analyze(prepare(base, head, fold))

    /** Input preparation belongs to the caller. Missing proof evidence remains a blocking diagnostic. */
    @Suppress("TooGenericExceptionCaught")
    @JvmSynthetic
    internal fun analyze(request: PreparedAnalysisRequest): AnalysisResult {
        val state = RunState(request)
        return try {
            if (request.changes.isEmpty()) validateUnchangedGitInputs(request.base, request.head)
            val scenarios = FoldDocument.parse(request.fold, configuration.maximumScenarios)
            state.initialize(scenarios)
            execute(request, scenarios, state)
            state.result()
        } catch (failure: Exception) {
            state.diagnostics += analysisFailureDiagnostic(state.stage, failure)
            state.result()
        }
    }

    private fun execute(workspace: PreparedAnalysisRequest, scenarios: List<FoldScenario>, state: RunState) {
        val changes = workspace.changes
        state.obligations = changes.size
        if (changes.isEmpty()) {
            state.ruleAudits.replaceAll { it.copy(message = "No base/head diff; graph selection was not executed") }
            return
        }
        state.stage = AnalysisStage.INPUT
        Files.createDirectories(configuration.cacheDirectory)
        val run = Files.createTempDirectory(configuration.cacheDirectory, "analysis-")
        try {
            val inputChanges = changes.filterNot {
                isProductionSourceChange(it, workspace.base.scopes.values, workspace.head.scopes.values,
                    workspace.base.sourceDirectory, workspace.head.sourceDirectory)
            }
            val inputProof = InputChangeProof(inputChanges, workspace.base.inventory, workspace.head.inventory)
            val suppliedOrigins = (workspace.base.scopes.values + workspace.head.scopes.values)
                .any { !it.hasNoProvenanceClaim() }
            // Claimed provenance must reach per-scope validation before unchanged inputs can discharge obligations.
            if (suppliedOrigins || prepareInputChangeProof(inputProof, changes.size, scenarios, state)) {
                analyzeScopes(workspace, changes, scenarios, state, run, inputProof)
            }
        } finally { cleanupDirectory(run) }
    }

    private fun analyzeScopes(
        workspace: PreparedAnalysisRequest, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        run: Path, inputProof: InputChangeProof,
    ) {
        val scopes = scopeRevisions(workspace, run)
        val selected = scopes.mapValues { (_, pair) ->
            val outputsChanged = scopeOutputsChanged(pair.base, pair.head)
            changesRequiringScopeProof(changes, inputProof.changes, outputsChanged) {
                belongsToScope(it, workspace, pair)
            }
        }.filterValues { it.isNotEmpty() }
        val uncovered = changes.filter { change -> selected.values.none { change in it } }
        val qualified = scopes.size > 1
        state.ruleAudits.clear()
        state.scenarios = emptyList()
        selected.keys.forEach { scope -> state.initialize(scopeScenarios(scenarios, scope, qualified)) }
        state.obligations = uncovered.size
        state.proved = 0
        uncovered.forEach { change ->
            // Source changes are owned by a scope by construction; uncovered changes are input obligations.
            state.diagnostics += Diagnostic(AnalysisStage.BUILD, DiagnosticCode.UNVERIFIED_BUILD_INPUT,
                "Changed input is not owned by any static input scope: " +
                    "${diagnosticLabel(change.basePath)} -> ${diagnosticLabel(change.headPath)}")
        }
        state.requireRuleMatch = !qualified
        val analyzed = selected.keys.toMutableSet()
        selected.entries.forEachIndexed { index, (scope, scopedChanges) ->
            analyzeScope(workspace, scopedChanges, scopeScenarios(scenarios, scope, qualified), state,
                scopes.getValue(scope), run.resolve("scope-$index"), inputProof)
        }
        if (qualified) {
            for (scope in scopes.keys - analyzed) {
                if (unmatchedScopeRules(scenarios, analyzed, state, knownOnly = false).isEmpty()) break
                val scopedScenarios = scopeScenarios(scenarios, scope, qualified)
                state.initialize(scopedScenarios)
                val directory = run.resolve("scope-${analyzed.size}")
                analyzed.add(scope)
                probeScopeRules(workspace, scopedScenarios, state, scopes.getValue(scope), directory, inputProof)
            }
            unmatchedScopeRules(scenarios, analyzed, state, knownOnly = true).forEach { (scenario, index) ->
                state.diagnostics += Diagnostic(AnalysisStage.FOLD, DiagnosticCode.UNMATCHED_RULE,
                    "$scenario: rule $index matched no input scope in either revision")
            }
        }
    }

    // A probe owns no change obligations. Only its rule evidence may affect the parent result.
    private fun probeScopeRules(
        workspace: PreparedAnalysisRequest, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions, run: Path, inputProof: InputChangeProof,
    ) {
        val probe = RunState(state.baseSha, state.headSha)
        probe.initialize(scenarios)
        probe.requireRuleMatch = false
        val names = scenarios.map { it.name }.toSet()
        val diagnostic = scopeRuleProbe(names, probe.ruleAudits) {
            analyzeScope(workspace, emptyList(), scenarios, probe, compiled, run, inputProof)
        }
        state.ruleAudits.removeAll { it.scenario in names }
        state.ruleAudits.addAll(probe.ruleAudits)
        state.unknownRuleMatches += probe.ruleAudits.filter { it.matchedCallCount == null }.map { it.scenario to it.ruleIndex }
        diagnostic?.let { state.diagnostics += it }
    }

    private fun analyzeScope(
        workspace: PreparedAnalysisRequest, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions, run: Path, inputProof: InputChangeProof,
    ) {
        val previousObligations = state.obligations
        val previousProved = state.proved
        state.obligations = changes.size
        state.proved = 0
        try {
            state.stage = AnalysisStage.BUILD
            val effective = compiled
            val foreignInputs = changes.filterNot { belongsToScope(it, workspace, compiled) }
            val inputScopeProof = InputChangeProof((inputProof.changes + foreignInputs).distinct(),
                effective.base.production, effective.head.production)
            analyzeSources(workspace, changes, scenarios, state, effective, run, inputScopeProof)
        } finally {
            state.obligations += previousObligations
            state.proved += previousProved
        }
    }

    private fun unmatchedScopeRules(
        scenarios: List<FoldScenario>, scopes: Set<String>, state: RunState, knownOnly: Boolean,
    ): List<Pair<String, Int>> = scenarios.flatMap { scenario ->
        val names = scopes.map { scope -> scopeScenarioName(scenario.name, scope) }.toSet()
        scenario.rules.indices.filter { index ->
            val counts = state.ruleAudits.filter { it.scenario in names && it.ruleIndex == index }
                .mapNotNull { it.matchedCallCount }
            counts.isNotEmpty() && counts.all { it == 0 } &&
                (!knownOnly || names.none { it to index in state.unknownRuleMatches })
        }.map { scenario.name to it }
    }

    private fun analyzeSources(
        workspace: PreparedAnalysisRequest, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions, run: Path, inputProof: InputChangeProof,
    ) {
        state.stage = AnalysisStage.SOURCE
        val sourceChanges = changes.filterNot { it in inputProof.changes }.toSet()
        val raw = compiled.base.hasNoProvenanceClaim() && compiled.head.hasNoProvenanceClaim()
        val unchanged = if (raw) null else unchangedSourceInputs(compiled.base, compiled.head, workspace.base.sourceDirectory,
            workspace.head.sourceDirectory, changes, sourceChanges)
        when {
            raw -> analyzeRawInputs(workspace, changes, scenarios, state, compiled)
            unchanged != null -> {
                state.obligations = changes.size
                recordUnchangedInputs(unchanged, scenarios, state,
                    "Complete normalized static inputs are unchanged after compiler provenance validation")
            }
            changes.isNotEmpty() && prepareInstrumentedInputProof(inputProof, changes.size, compiled, workspace, scenarios, state) -> Unit
            else -> analyzeMappedSources(workspace, changes, scenarios, state, compiled, run, inputProof)
        }
    }

    private fun analyzeMappedSources(
        workspace: PreparedAnalysisRequest, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions, run: Path, inputProof: InputChangeProof,
    ) {
        val sourceChanges = changes.filterNot { it in inputProof.changes }.toSet()
        val outputProof = scopeOutputProof(inputProof, compiled.base, compiled.head, sourceChanges)
        val origins = prepareOrigins(
            compiled.base, compiled.head, workspace.base.sourceDirectory, workspace.head.sourceDirectory, changes,
            run.resolve("normalized"),
        )
        origins.diagnostics.forEach { state.diagnostics += Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.UNVERIFIED_ORIGIN, it) }
        val oldProject = origins.base
        val newProject = origins.head
        val obligations = sourceChanges
            .flatMap { change -> obligations(workspace, change, oldProject, newProject, state) }
        state.obligations = obligations.size + outputProof.changes.size
        val passed = BooleanArray(obligations.size) { true }
        var inputsProved = true
        scenarios.forEach { scenario ->
            val comparison = compareScenario(oldProject, newProject, scenario, state, outputProof.requiresResourceAnalysis,
                origins.diagnostics.isEmpty())
            val confirmedDifferences = mutableSetOf<String>()
            obligations.forEachIndexed { index, obligation ->
                if (!checkObligation(obligation, comparison, scenario.name, state, confirmedDifferences)) passed[index] = false
            }
            val inputPassed = checkBuildInputs(outputProof, comparison, scenario.name, state, confirmedDifferences)
            inputsProved = inputsProved && inputPassed
        }
        state.proved = passed.count { it } + if (inputsProved) outputProof.changes.size else 0
    }

    /** No optional source catalog is needed to compare complete raw static inputs without owner pairing. */
    private fun analyzeRawInputs(
        workspace: PreparedAnalysisRequest, changes: List<ChangedFile>, scenarios: List<FoldScenario>, state: RunState,
        compiled: CompiledRevisions,
    ) {
        val sourceChanges = changes.filter {
            isProductionSourceChange(it, workspace.base.scopes.values, workspace.head.scopes.values,
                workspace.base.sourceDirectory, workspace.head.sourceDirectory) && belongsToScope(it, workspace, compiled)
        }.toSet()
        val proof = InputChangeProof(changes, compiled.base.production, compiled.head.production, sourceChanges)
        val unchangedInventory = proof.unchanged && proof.gap() == null
        if (changes.isNotEmpty() && unchangedInventory && identicalStaticScopeBytes(compiled.base, compiled.head)) {
            recordUnchangedInputs(proof, scenarios, state,
                "Complete original packaged bytecode, resources and ordered dependencies are unchanged")
            return
        }
        val old = OriginProject(compiled.base, compiled.base.classes,
            readOrigins(compiled.base, workspace.base.sourceDirectory), emptyMap())
        val new = OriginProject(compiled.head, compiled.head.classes,
            readOrigins(compiled.head, workspace.head.sourceDirectory), emptyMap())
        val attribution = RunState(state.baseSha, state.headSha)
        val mapped = sourceChanges.flatMap { obligations(workspace, it, old, new, attribution) }
        state.obligations = changes.size
        var proved = true
        scenarios.forEach { scenario ->
            val comparison = compareScenario(old, new, scenario, state, proof.requiresResourceAnalysis, false)
            val details = RunState(state.baseSha, state.headSha)
            val confirmed = if (attribution.diagnostics.isEmpty()) {
                attributeRawDifferences(mapped, comparison, scenario.name, details)
            } else emptySet()
            // Attribution can explain a raw failure; only the complete raw comparison can prove this input controlled.
            val inputPassed = checkBuildInputs(proof, comparison, scenario.name, state, confirmed)
            if (!inputPassed) {
                state.findings += details.findings
                state.reasons += details.reasons
                state.diagnostics += details.diagnostics.filterNot { it in state.diagnostics }
            }
            proved = inputPassed && proved
        }

        state.proved = if (proved) changes.size else 0
    }

    private fun attributeRawDifferences(
        obligations: List<ProofObligation>, comparison: SnapshotComparison, scenario: String, details: RunState,
    ): Set<String> {
        val confirmed = mutableSetOf<String>()
        obligations.forEach { obligation ->
            val before = details.findings.size
            checkObligation(obligation, comparison, scenario, details, mutableSetOf())
            if (details.findings.size > before && comparison.analysisGaps(obligation).isEmpty()) {
                confirmed += comparison.differences(obligation)
            }
        }
        return confirmed
    }

    private fun prepareInstrumentedInputProof(
        proof: InputChangeProof, changeCount: Int, compiled: CompiledRevisions, workspace: PreparedAnalysisRequest,
        scenarios: List<FoldScenario>, state: RunState,
    ): Boolean {
        if (proof.changes.size != changeCount) return false
        val failures = readOrigins(compiled.base, workspace.base.sourceDirectory).diagnostics.map { "base: $it" } +
            readOrigins(compiled.head, workspace.head.sourceDirectory).diagnostics.map { "head: $it" }
        return when {
            failures.isNotEmpty() -> false // prepareOrigins retains the blockers and permits independent graph evidence.
            proof.unchangedClasses(compiled.base.classes, compiled.head.classes) -> {
                recordUnchangedInputs(proof, scenarios, state,
                    "Production outputs are unchanged after validating and removing GraphFold compiler-origin instrumentation")
                true
            }
            else -> false
        }
    }

    private fun prepareInputChangeProof(
        proof: InputChangeProof, changeCount: Int, scenarios: List<FoldScenario>, state: RunState,
    ): Boolean {
        if (proof.changes.isEmpty()) return true
        return when {
            proof.changes.size == changeCount && proof.unchanged -> {
                recordUnchangedInputs(proof, scenarios, state,
                    "Normalized bytecode, resources and external dependencies are unchanged")
                false
            }
            else -> true
        }
    }

    private fun checkBuildInputs(
        proof: InputChangeProof, comparison: SnapshotComparison, scenario: String, state: RunState, confirmedDifferences: Set<String>,
    ): Boolean {
        if (proof.changes.isEmpty()) return true
        return checkProductionInputs(proof, comparison, scenario, state, confirmedDifferences)
    }

    private fun checkProductionInputs(
        proof: InputChangeProof, comparison: SnapshotComparison, scenario: String, state: RunState, confirmedDifferences: Set<String>,
    ): Boolean {
        val resourcesProved = proof.resourcesUnreachable(
            comparison.reachableSnapshots(), comparison.resourceDependenciesAnalyzed, comparison.resourceEntryReads,
        )
        val gap = proof.gap(resourcesProved = resourcesProved)
        if (gap != null) {
            state.diagnostics += gap
            state.reasons += proof.reasons(ReasonCode.ANALYSIS_INCOMPLETE, scenario, gap.message)
            return false
        }
        val analysisGaps = if (resourcesProved) comparison.resourceAnalysisGaps() else comparison.analysisGaps()
        analysisGaps.forEach { state.diagnostics += Diagnostic(AnalysisStage.GRAPH, DiagnosticCode.INCOMPLETE_GRAPH, it) }
        val differences = comparison.wholeProgramDifferences()
        return if (proof.attributedSourceDifference(differences, analysisGaps, confirmedDifferences)) {
            state.reasons += proof.reasons(ReasonCode.SURVIVING_DIFFERENCE, scenario,
                "Actual production differences are covered by verified source findings: ${differences.joinToString()}")
            false
        } else if (differences.isNotEmpty() && analysisGaps.isEmpty()) {
            // Complete original JVM differences need no source identity or compiler-origin attribution.
            val message = "Production class differences survive selected control folding: ${differences.joinToString()}"
            state.reasons += proof.reasons(ReasonCode.SURVIVING_DIFFERENCE, scenario, message)
            state.diagnostics += Diagnostic(AnalysisStage.COMPARE, DiagnosticCode.PRODUCTION_OUTPUT_CHANGED, message)
            false
        } else {
            val proved = differences.isEmpty() && analysisGaps.isEmpty()
            val message = if (proved) "Production outputs are unchanged or changed resource keys have no surviving reader; " +
                "the entire analyzed production program is equivalent after folding"
            else "Folded production program has differences or gaps; build input effects cannot be independently attributed: " +
                (differences + analysisGaps).joinToString()
            state.reasons += proof.reasons(
                if (proved) ReasonCode.FOLDED_EQUIVALENT else ReasonCode.ANALYSIS_INCOMPLETE, scenario, message,
            )
            if (!proved) state.diagnostics += Diagnostic(AnalysisStage.COMPARE, DiagnosticCode.UNVERIFIED_BUILD_INPUT, message)
            proved
        }
    }

    private fun compareScenario(
        base: OriginProject, head: OriginProject, scenario: FoldScenario, state: RunState, dependencyResources: Boolean,
        originsVerified: Boolean,
    ): SnapshotComparison {
        state.stage = AnalysisStage.GRAPH
        val old = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.BASE, index, update) }
            .analyzeOriginProject(base, scenario.rules, dependencyResources)
        val new = GraphiteAnalyzer { index, update -> state.audit(scenario.name, Revision.HEAD, index, update) }
            .analyzeOriginProject(head, scenario.rules, dependencyResources)
        state.diagnostics += graphDiagnostics(old.diagnostics, new.diagnostics)
        scenario.rules.indices.forEach { index ->
            if (state.requireRuleMatch && old.matchedRules[index] == 0 && new.matchedRules[index] == 0) {
                state.diagnostics += Diagnostic(
                    AnalysisStage.FOLD, DiagnosticCode.UNMATCHED_RULE, "${scenario.name}: rule $index matched neither revision",
                )
            }
        }
        state.stage = AnalysisStage.COMPARE
        return SnapshotComparison(old, new, originsVerified)
    }

    private fun checkObligation(
        obligation: ProofObligation, comparison: SnapshotComparison, scenario: String, state: RunState,
        confirmedDifferences: MutableSet<String>,
    ): Boolean {
        val gaps = comparison.analysisGaps(obligation)
        gaps.forEach { state.diagnostics += Diagnostic(AnalysisStage.GRAPH, DiagnosticCode.INCOMPLETE_GRAPH, it) }
        val differences = comparison.differences(obligation)
        val proved = differences.isEmpty() && gaps.isEmpty()
        val reason = when {
            gaps.isNotEmpty() -> ReasonCode.ANALYSIS_INCOMPLETE
            !proved -> ReasonCode.SURVIVING_DIFFERENCE
            comparison.isUnreachable(obligation) -> ReasonCode.UNREACHABLE
            else -> ReasonCode.FOLDED_EQUIVALENT
        }
        state.reasons += VerdictReason(
            obligation.changes, reason, if (gaps.isEmpty()) reasonMessage(reason, differences) else gaps.joinToString(),
            obligation.locations, scenario, obligation.basePath, obligation.headPath,
        )
        if (differences.isNotEmpty() && obligation.locations.isEmpty()) {
            state.diagnostics += Diagnostic(AnalysisStage.COMPARE, DiagnosticCode.UNLOCATED_MOVE_DIFFERENCE,
                "Moving ${diagnosticLabel(obligation.basePath)} to ${diagnosticLabel(obligation.headPath)} " +
                    "changes JVM representation: ${differences.joinToString()}")
        } else if (differences.isNotEmpty()) {
            val prefix = if (comparison.originsVerified) "Surviving program representation differs" else
                "Surviving bytecode differs under original JVM identity; compiler-origin mappings are unverified"
            val message = "$prefix: ${differences.joinToString()}"
            state.findings += Finding(
                digest("$scenario|${obligation.locations}|$message"), FindingKind.UNVERIFIABLE_CHANGE,
                "folded-program-equivalence", message, obligation.locations,
                differences.map { Evidence("Remaining difference in $it") }, listOf(scenario),
            )
            if (comparison.originsVerified && gaps.isEmpty()) confirmedDifferences.addAll(differences)
        }
        return proved
    }
}

private fun recordUnchangedInputs(proof: InputChangeProof, scenarios: List<FoldScenario>, state: RunState, message: String) {
    state.proved = proof.changes.size
    scenarios.forEach { state.reasons += proof.reasons(ReasonCode.BUILD_OUTPUT_UNCHANGED, it.name, message) }
    val names = scenarios.map { it.name }.toSet()
    state.ruleAudits.replaceAll {
        if (it.scenario in names) it.copy(message = "Production outputs are unchanged; graph selection was not executed") else it
    }
}


private fun obligations(
    workspace: PreparedAnalysisRequest,
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
    val old = source(workspace.base.sourceDirectory, change.basePath, Revision.BASE, base, state)
    val new = source(workspace.head.sourceDirectory, change.headPath, Revision.HEAD, head, state)
    state.diagnostics += sourceDiagnostics(old, new)
    val planned = planSourceChanges(old, new, change)
    if (planned.isEmpty()) {
        state.diagnostics += Diagnostic(
            AnalysisStage.SOURCE, DiagnosticCode.UNMAPPED_CHANGE, "Cannot bind ${change.headPath ?: change.basePath} to a declaration",
        )
    }
    return planned
}

@Suppress("TooGenericExceptionCaught") // Optional mapping failures cannot prevent independent bytecode folding.
private fun source(root: Path, path: String?, revision: Revision, project: OriginProject, state: RunState): IndexedSource? = try {
    path?.takeIf { scopeContainsSource(project.compiled, root, it) }?.let {
        bindOrigins(indexSource(root, it, revision, project), revision, project.catalog, project.owners)
    }
} catch (failure: Exception) {
    state.diagnostics += sourceFailureDiagnostic(revision, path, failure)
    null
}

private fun reasonMessage(reason: ReasonCode, differences: List<String>): String = when (reason) {
    ReasonCode.UNREACHABLE -> "Selected controls removed every static path to this change from the same input before folding"
    ReasonCode.FOLDED_EQUIVALENT -> "No surviving difference in the analyzed representation"
    else -> "Surviving differences: ${differences.joinToString()}"
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private data class CompiledRevisions(val base: StaticScope, val head: StaticScope)

/** Compare additions/removals against empty bytecode, never reject a change just because its scope is new. */
private fun scopeRevisions(request: PreparedAnalysisRequest, output: Path): Map<String, CompiledRevisions> =
    (request.base.scopes.keys + request.head.scopes.keys).withIndex().associate { (index, name) ->
        val before = request.base.scopes[name] ?: emptyScope(output.resolve("absent-base-$index"))
        val after = request.head.scopes[name] ?: emptyScope(output.resolve("absent-head-$index"))
        name to CompiledRevisions(before, after)
    }

private fun emptyScope(directory: Path): StaticScope = StaticScope(
    Files.createDirectories(directory), emptyList(), emptyList(),
    production = ContentInventory(emptyMap(), emptyMap(), resourceConsumersKnown = true),
)

private fun belongsToScope(change: ChangedFile, workspace: PreparedAnalysisRequest, pair: CompiledRevisions): Boolean =
    change.basePath?.let { scopeContainsSource(pair.base, workspace.base.sourceDirectory, it) } == true ||
        change.headPath?.let { scopeContainsSource(pair.head, workspace.head.sourceDirectory, it) } == true

private fun scopeScenarioName(scenario: String, scope: String): String = "$scenario [scope $scope]"

private fun scopeScenarios(scenarios: List<FoldScenario>, scope: String, qualified: Boolean): List<FoldScenario> =
    if (qualified) scenarios.map { it.copy(name = scopeScenarioName(it.name, scope)) } else scenarios

private class RunState(var baseSha: String, var headSha: String) {
    constructor(request: PreparedAnalysisRequest) : this(request.base.sha, request.head.sha)
    var stage = AnalysisStage.INPUT
    var scenarios = emptyList<String>()
    var obligations = 0
    var proved = 0
    var requireRuleMatch = true
    val reasons = mutableListOf<VerdictReason>()
    val findings = mutableListOf<Finding>()
    val diagnostics = mutableListOf<Diagnostic>()
    val ruleAudits = mutableListOf<RuleAudit>()
    val unknownRuleMatches = hashSetOf<Pair<String, Int>>()

    fun initialize(folds: List<FoldScenario>) {
        scenarios = scenarios + folds.map { it.name }
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

/** Bind both diff revisions and preserve move-only declaration obligations. */
internal fun planSourceChanges(old: IndexedSource?, new: IndexedSource?, change: ChangedFile): List<ProofObligation> {
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
            basePath = change.basePath, headPath = change.headPath, inlined = group.any { it.inlined },
        )
    }
}


internal fun analysisFailureDiagnostic(stage: AnalysisStage, failure: Exception): Diagnostic {
    if (failure is InterruptedException) {
        Thread.currentThread().interrupt()
        return Diagnostic(stage, DiagnosticCode.ANALYSIS_CANCELLED, "Analysis was interrupted")
    }
    return Diagnostic(stage, DiagnosticCode.ANALYSIS_FAILED, failure.message ?: failure.javaClass.simpleName)
}

/** Missing source evidence limits precision; cancellation must escape to the analysis boundary. */
internal fun sourceFailureDiagnostic(revision: Revision, path: String?, failure: Exception): Diagnostic {
    if (failure is InterruptedException) throw failure
    return Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.SOURCE_RESOLUTION_FAILED,
        "Cannot map $revision source ${diagnosticLabel(path)}: $failure")
}

/** Keep revision ownership when exposing whole-graph inventory failures. */
internal fun graphDiagnostics(base: List<String>, head: List<String>): List<Diagnostic> =
    (base.map { "base: $it" } + head.map { "head: $it" }).map {
        Diagnostic(AnalysisStage.GRAPH, DiagnosticCode.INCOMPLETE_GRAPH, it)
    }

/** Source resolution gaps remain blockers even when other declarations bind successfully. */
internal fun sourceDiagnostics(base: IndexedSource?, head: IndexedSource?): List<Diagnostic> =
    (base?.diagnostics.orEmpty() + head?.diagnostics.orEmpty()).map {
        Diagnostic(AnalysisStage.SOURCE, DiagnosticCode.SOURCE_RESOLUTION_FAILED, it)
    }

private fun indexSource(root: Path, path: String, revision: Revision, project: OriginProject): IndexedSource =
    if (path.endsWith(".kt")) {
        KotlinSourceIndex(project.sourceIndexClasses(root.resolve(path)), project.compiled.sourceOutputs)
            .use { it.index(root, path, revision) }
    } else {
        // obligations() only receives changes whose nonnull base/head paths are JVM sources.
        val inputs = javaCompilerInputs(project.compiled, root.resolve(path))
        JavaSourceIndex(inputs.roots, inputs.dependencies).use { it.index(root, path, revision) }
    }
