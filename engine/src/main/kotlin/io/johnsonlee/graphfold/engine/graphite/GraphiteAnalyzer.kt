package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.LeafApplicationDispatch
import io.johnsonlee.graphfold.engine.bytecode.asm.BytecodeInventory
import io.johnsonlee.graphfold.engine.bytecode.asm.ResourceDeclarations
import io.johnsonlee.graphfold.engine.bytecode.asm.inlineControlRemovedMethods

import io.johnsonlee.graphfold.engine.FoldReachability
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Selects fold targets through Graphite and captures folded IR together with independent bytecode metadata.
 * Returned diagnostics describe incomplete analysis and must be checked before using a snapshot as proof.
 */
class GraphiteAnalyzer internal constructor(private val onAudit: (Int, RuleAuditUpdate) -> Unit) {
    /** Creates an analyzer without an external rule-audit callback. */
    constructor() : this({ _, _ -> })

    /**
     * Resolves [rules] against this artifact and captures the resulting folded application snapshot.
     * Selection is evaluated for this invocation; matches from another revision are not reused.
     *
     * @param artifact Prepared application class directory to load.
     * @param rules Fold rules in application order; an empty list captures the program without folding.
     * @param dependencies Resolved dependency JARs and class directories used to complete the frontend's classpath.
     * @param dependencyResources Expand folded dependency bodies to account for indirect resource reads. This is only
     * needed for resource proofs; dependency methods remain excluded from application entry points and structural comparison.
     * @return Structural evidence and any recoverable omissions reported by inventory or frontend analysis.
     * @throws IllegalArgumentException if the input is not a prepared class directory, or a fold selection has an invalid shape
     * or cannot be applied safely.
     * @throws IllegalStateException if selection or snapshot capture fails, including missing method observations.
     */
    fun analyze(
        artifact: Path, rules: List<FoldRule>, dependencies: List<Path> = emptyList(), dependencyResources: Boolean = false,
    ): ProgramSnapshot = analyzeArtifact(artifact, rules, dependencies, dependencyResources)

    private fun analyzeArtifact(
        artifact: Path, rules: List<FoldRule>, dependencies: List<Path>, dependencyResources: Boolean,
    ): ProgramSnapshot {
        requirePreparedClasses(artifact)
        val inventory = BytecodeInventory().also { it.read(artifact) }
        inventory.overrides = inventory.inheritedEntries.resolve(
            dependencies, inventory.diagnostics, inventory.methodDiagnostics)
        inventory.missingReferences(dependencies).forEach { inventory.diagnostics.add("Unresolved class reference: $it") }
        val resources = if (dependencyResources) ResourceDeclarations(listOf(artifact) + dependencies) else null
        return AnalysisInputs(artifact, dependencies).use { inputs ->
            val snapshot = analyzeInputs(inputs.path, rules, inventory, RuleAuditCapture(onAudit), resources,
                AnalysisContext())
            snapshot
        }
    }

    internal fun analyzeOriginProject(
        project: OriginProject, rules: List<FoldRule>, dependencyResources: Boolean = false,
    ): ProgramSnapshot {
        val compiled = project.compiled
        requirePreparedClasses(compiled.classes)
        requirePreparedClasses(project.sourceClasses)
        val dependencies = (compiled.dependencies +
            if (dependencyResources) compiled.production?.resourceDependencies.orEmpty() else emptyList()).distinct()
        if (!project.requiresOriginAnalysis()) {
            return analyzeArtifact(compiled.classes, rules, dependencies, dependencyResources)
        }
        val inventory = originInventory(project, dependencies)
        val audit = RuleAuditCapture(onAudit)
        val exposure = ExposureProvenance()
        val reachability = FoldReachability()
        val unchangedOwners = project.owners.all { it.key == it.value }
        val resources = if (dependencyResources) ResourceDeclarations(listOf(compiled.classes) + dependencies) else null
        val resolveSelection = { graph: Graph, rule: FoldSites, index: Int ->
            resolve(graph, rule) { audit.selected(index, it); exposure.select(index, it) }
        }
        val selected = if (rules.isEmpty()) emptyList() else {
            val original = BytecodeInventory().also { it.read(project.sourceClasses) }
            AnalysisInputs(project.sourceClasses, dependencies).use { inputs ->
                val graph = selectionGraph(inputs.path, LoaderConfig(
                    interproceduralDataflow = true,
                    includePackages = original.classes.toList(), includeLibraries = true,
                    dependencyBodies = dependencyBodyScope(dependencyResources),
                    verbose = artifactDiagnosticCollector(inventory.diagnostics),
                )) { if (unchangedOwners) exposure.capture(it) }
                val completeness = GraphiteCompleteness(graph)
                if (unchangedOwners) captureReachability(reachability, graph, completeness)
                rules.forEachIndexed { index, rule ->
                    if (rule is ConstantFold) exposure.select(index, constantSelection(graph, rule))
                }
                originFoldSelection(graph, rules, project.owners, resolveSelection, audit) {
                    requireCompleteSelection(graph, it, completeness)
                }
            }
        }
        return AnalysisInputs(compiled.classes, dependencies).use { inputs ->
            val snapshot = analyzeInputs(inputs.path, selected, inventory, audit, resources,
                AnalysisContext(exposure, reachability,
                    if (unchangedOwners && project.catalog.diagnostics.isEmpty()) project.catalog.inlineEvidence else emptyList(),
                    project.sourceClasses))
                .copy(originalOwners = project.owners.entries.associate { (original, normalized) -> normalized to original })
            snapshot
        }
    }

    private fun originInventory(
        project: OriginProject, dependencies: List<Path>,
    ): BytecodeInventory {
        // Class names remain observable when anonymous owners were normalized for structural alignment.
        val inventory = BytecodeInventory(normalizedRuntimeNames = project.owners.any { it.key != it.value })
            .also { it.read(project.compiled.classes) }
        project.methodDiagnostics.forEach { (identity, gaps) ->
            if (identity !in inventory.methods) {
                inventory.diagnostics.add("Origin observation has no normalized method declaration: $identity")
            }
            inventory.methodDiagnostics[identity] = inventory.methodDiagnostics[identity].orEmpty() + gaps
        }
        inventory.overrides = inventory.inheritedEntries.resolve(
            dependencies, inventory.diagnostics, inventory.methodDiagnostics)
        inventory.missingReferences(dependencies).forEach {
            inventory.diagnostics.add("Unresolved class reference: $it")
        }
        return inventory
    }

    private fun analyzeInputs(
        artifact: Path, rules: List<FoldRule>, inventory: BytecodeInventory, audit: RuleAuditCapture,
        resources: ResourceDeclarations?,
        context: AnalysisContext,
    ): ProgramSnapshot {
        val diagnostics = mutableListOf<String>()
        diagnostics.addAll(inventory.diagnostics)
        diagnostics.addAll(resources?.diagnostics.orEmpty())
        val log = artifactDiagnosticCollector(diagnostics)
        val exposure = context.selection ?: ExposureProvenance()
        val reachability = context.reachability
        val resolved = if (context.selection == null && rules.isNotEmpty()) {
            selectInputs(artifact, rules, selectionConfig(inventory.classes, log, resources != null), audit, exposure, reachability)
        } else rules
        val sink = SnapshotSink(if (resources == null) inventory.classes else resources.classes,
            resources != null, resources != null && resources.trustedIntrinsics,
            inventory.disposablePrimitiveMethods, exposure)
        val config = LoaderConfig(
            folding = resolved.takeIf { it.isNotEmpty() }?.let { FoldPlan(it) },
            verbose = log,
            includePackages = inventory.classes.toList(), includeLibraries = true,
            dependencyBodies = dependencyBodyScope(resources != null),
            // Selection needs these edges independently; ordinary folded snapshots consume bodies and call sites only.
            interproceduralDataflow = resources != null,
        )
        val loader = JavaProjectLoader(config, false)
        // Captures stay local until the observed load and its final evidence reconciliation succeed.
        val observed = loader.loadObserved(artifact, sink::capture)
        val folded = observed.graph
        val report = observed.foldReport
        val dispatch = LeafApplicationDispatch(artifact, inventory.classes)
        val completeness = GraphiteCompleteness(folded, sink.observations, inventory.classes,
            modeledResourceCall = sink::modelsResourceCall,
            provenDispatch = { dispatch.proves(it, sink.methods) })
        diagnostics.addAll(completeness.diagnostics)
        val incompleteResources = completeness.incompleteResourceMethods(sink::modelsResourceCall)
        sink.methods.replaceAll { identity, method ->
            if (identity in incompleteResources) method.copy(resourceReads = method.resourceReads + ResourceRead(null, null)) else method
        }
        val resourceAnalysis = resourceAnalysis(resources, inventory, sink, folded)
        val methods = foldedMethods(inventory, sink, folded, resourceAnalysis)
        // JVM recovery is reconciled through the method receipts above.
        report?.outcomes?.forEachIndexed { index, outcome ->
            audit.applied(index, outcome.matched)
            outcome.unsupported.forEach { diagnostics.add("${it.method}: ${it.reason}") }
            // A bare selection intentionally applies to every caller. Graphite's scope hint is not
            // incomplete analysis; unresolved argument constraints remain in unsupported above.
        }
        (inventory.methods - sink.methods.keys).forEach { diagnostics.add("Frontend omitted declared method: $it") }
        (resources?.methods.orEmpty() - sink.methods.keys).forEach { diagnostics.add("Frontend omitted resource dependency method: $it") }
        val snapshot = ProgramSnapshot(methods, inventory.declarations, diagnostics.distinct(),
            report?.outcomes?.map { it.matched }.orEmpty(), inventory.fields, inventory.classShapes,
            inventory.ignoredFields, inventory.entryClasses, inventory.externalEntries,
            inventory.initialization.dependencies(), inventory.overrides, inventory.enclosingClasses,
            (inventory.methodDiagnostics.keys + completeness.methodDiagnostics.keys).associateWith {
                inventory.methodDiagnostics[it].orEmpty() + completeness.methodDiagnostics[it].orEmpty()
            },
            resourceDependenciesAnalyzed = resources != null && diagnostics.isEmpty(),
            resourceEntryReads = resourceEntries(inventory.classes, resourceAnalysis),
            initializationTriggerMethods = inventory.initialization.initializationTriggerMethods(),
            structuralClassShapes = inventory.structuralClassShapes)
        val inlineRemoved = context.removedInlineMethods(snapshot, resolved, sink.appliedRules)
        return snapshot.copy(controlRemovedMethods = reachability.witnessed(snapshot) + inlineRemoved)
    }

    private fun foldedMethods(
        inventory: BytecodeInventory, sink: SnapshotSink, folded: Graph, resourceAnalysis: DependencyResourceAnalysis?,
    ): Map<String, MethodSnapshot> {
        val dispatch = folded.nodes(CallSiteNode::class.java).groupBy { it.caller.identity }
        return sink.methods.filterKeys { it in inventory.methods }.mapValues { (identity, method) ->
            method.copy(calls = method.calls + dispatch[identity].orEmpty().map { it.callee.identity }
                .filterNot { it in sink.discardedCalls.getValue(identity) },
                resourceReads = if (resourceAnalysis == null) method.resourceReads else resourceAnalysis.reads(identity))
        }
    }

    private fun resourceAnalysis(
        resources: ResourceDeclarations?, inventory: BytecodeInventory, sink: SnapshotSink, graph: Graph,
    ): DependencyResourceAnalysis? = resources?.let {
        DependencyResourceAnalysis(graph, sink.methods, sink.resourceCalls, it.initialization(), sink.resourceInitializers,
            fieldOwner = it::fieldOwner, inheritedMethods = inventory.classes.associateWith(it::inheritedMethods),
            entryInitializers = inventory.classes.associateWith(it::entryInitializers))
    }

    private fun resourceEntries(owners: Set<String>, analysis: DependencyResourceAnalysis?): Map<String, List<ResourceRead>> =
        analysis?.let { owners.associateWith(it::entryReads) }.orEmpty()

    private fun selectInputs(
        artifact: Path, rules: List<FoldRule>, config: LoaderConfig, audit: RuleAuditCapture, exposure: ExposureProvenance,
        reachability: FoldReachability,
    ): List<FoldRule> {
        val plain = selectionGraph(artifact, config, exposure::capture)
        val completeness = GraphiteCompleteness(plain)
        captureReachability(reachability, plain, completeness)
        return rules.mapIndexed { index, rule ->
            runCatching {
                requireCompleteSelection(plain, rule, completeness)
                when (rule) {
                    is FoldSites -> resolve(plain, rule) { audit.selected(index, it); exposure.select(index, it) }
                    is ConstantFold -> rule.also {
                        val sites = constantSelection(plain, it)
                        audit.selected(index, sites)
                        exposure.select(index, sites)
                    }
                }
            }.onFailure { audit.failed(index, it) }.getOrThrow()
        }
    }

    internal fun resolve(graph: Graph, rule: FoldSites, selected: (List<CallSiteNode>) -> Unit = {}): FoldSites {
        val result = graph.query(rule.select)
        require(result.columns.size == 1) { "Fold select must return exactly one CallSite column" }
        val column = result.columns.single()
        val sites = result.rows.map { row ->
            val value = row[column] as? Map<*, *> ?: error("Fold select must return CallSite nodes")
            require(value["type"] == "CallSiteNode") { "Fold select returned a non-CallSite value" }
            val id = value["id"] as? Number ?: error("Fold select returned a node without an id")
            graph.node(NodeId(id.toInt())) as? CallSiteNode ?: error("Fold select returned an unknown CallSite")
        }
        selected(sites)
        val selectedIds = sites.map { it.id }.toSet()
        val children = graph.nodes(CallSiteNode::class.java).filter { it.origin != null }.groupBy { it.origin }
        fun leaves(site: CallSiteNode, seen: Set<NodeId> = emptySet()): List<CallSiteNode> {
            require(site.id !in seen) { "Cycle in derived call origins" }
            return children[site.id]?.flatMap { leaves(it, seen + site.id) } ?: listOf(site)
        }
        val keys = linkedSetOf<CallSiteKey>()
        val resultTypes = linkedMapOf<CallSiteKey, String>()
        for (site in sites) {
            val (origin, ordinal) = bytecodeOrigin(graph, site) ?: continue
            val targets = leaves(origin)
            require(origin.id in selectedIds || targets.all { it.id in selectedIds } && completeDispatch(graph, origin, children)) {
                "Selecting incomplete targets of a shared functional call would broaden the fold"
            }
            val key = CallSiteKey(
                origin.caller.signature, origin.caller.descriptor, origin.callee.signature,
                origin.callee.descriptor, ordinal,
            )
            keys.add(key)
            if (origin.callee.returnType.className == "java.lang.Object" && targets.any { it.id != origin.id }) {
                val types = targets.map { it.callee.returnType.className }.distinct()
                require(types.size == 1) { "Functional call has ambiguous erased result types" }
                resultTypes[key] = types.single()
            }
        }
        // Re-resolve every revision: selected keys from another artifact are never reused.
        return rule.copy(selected = keys, resultTypes = resultTypes, provenance = null)
    }

    private fun bytecodeOrigin(graph: Graph, site: CallSiteNode): Pair<CallSiteNode, Int>? {
        var origin = site
        val seen = mutableSetOf<NodeId>()
        while (true) {
            val ordinal = origin.ordinal ?: error("CallSite has no ordinal")
            if (ordinal >= 0) return origin to ordinal
            require(seen.add(origin.id)) { "Cycle in derived call origins" }
            val parent = origin.origin ?: return null // A creation edge has no bytecode invocation.
            origin = graph.node(parent) as? CallSiteNode ?: error("Invalid derived call origin")
        }
    }
}

private fun requireCompleteSelection(graph: Graph, rule: FoldRule, completeness: GraphiteCompleteness) {
    val failures = selectionEvidenceDiagnostics(graph, rule, completeness)
    require(failures.isEmpty()) { failures.joinToString("\n") }
}

private fun selectionGraph(artifact: Path, config: LoaderConfig, observer: (FoldedMethodObservation) -> Unit): Graph {
    val loader = JavaProjectLoader(config, false)
    return loader.loadObserved(artifact, observer).graph
}

private fun selectionConfig(owners: Set<String>, log: (String) -> Unit, resources: Boolean): LoaderConfig = LoaderConfig(
    interproceduralDataflow = true, verbose = log, includePackages = owners.toList(), includeLibraries = true,
    dependencyBodies = dependencyBodyScope(resources),
)

/** Eager declared bodies retain inherited APIs and exposed field entries without ordinary application call sites. */
private fun dependencyBodyScope(resources: Boolean): DependencyBodyScope =
    if (resources) DependencyBodyScope.Declared(artifacts = listOf("*")) else DependencyBodyScope.None

/** A derived target cannot authorize folding an invocation with additional unresolved alternatives. */
private fun completeDispatch(graph: Graph, origin: CallSiteNode, children: Map<NodeId?, List<CallSiteNode>>): Boolean {
    val evidence = graph.analysisCompleteness() ?: return false
    val gaps = evidence.callSiteGaps
    fun complete(site: CallSiteNode): Boolean = site.id.value !in gaps && children[site.id].orEmpty().all(::complete)
    return complete(origin)
}

/** Archive selection and expansion must finish in the adapter before graph construction. */
private fun requirePreparedClasses(classes: Path) {
    require(Files.isDirectory(classes)) { "Application input must be a prepared class directory: $classes" }
}

/** Selection provenance and dispatch assumptions belong to the same selected archive analysis. */
private data class AnalysisContext(
    val selection: ExposureProvenance? = null, val reachability: FoldReachability = FoldReachability(),
    val inlineEvidence: List<io.johnsonlee.graphfold.engine.inputs.InlineEvidence> = emptyList(), val inlineClasses: Path? = null,
) {
    fun removedInlineMethods(snapshot: ProgramSnapshot, rules: List<FoldRule>, applied: Map<String, Map<Int, Int>>): Set<String> =
        if (inlineClasses != null && snapshot.diagnostics.isEmpty() && snapshot.methodDiagnostics.values.all { it.isEmpty() }) {
            inlineControlRemovedMethods(inlineClasses, inlineEvidence, rules, applied)
        } else emptySet()
}

private fun OriginProject.requiresOriginAnalysis(): Boolean =
    owners.isNotEmpty() || methodDiagnostics.isNotEmpty() || catalog.inlineEvidence.isNotEmpty()

/** Translate Graphite selection evidence into the engine's same-input control proof. */
internal fun captureReachability(proof: FoldReachability, graph: Graph, completeness: GraphiteCompleteness) {
    val complete = completeness.diagnostics.isEmpty() && completeness.methodDiagnostics.isEmpty()
    val calls = if (complete) graph.nodes(CallSiteNode::class.java)
        .filter { it.origin == null && (it.ordinal ?: -1) >= 0 }
        .groupBy { it.caller.identity }.mapValues { (_, sites) -> sites.map { it.callee.identity }.toSet() }
    else emptyMap()
    proof.capture(calls, complete)
}
