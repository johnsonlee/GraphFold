package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
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
     * @param artifact Application class directory or archive to load.
     * @param rules Fold rules in application order; an empty list captures the program without folding.
     * @param dependencies Resolved dependency JARs and class directories used to complete the frontend's classpath.
     * @param dependencyResources Expand folded dependency bodies to account for indirect resource reads. This is only
     * needed for resource proofs; dependency methods remain excluded from application entry points and structural comparison.
     * @return Structural evidence and any recoverable omissions reported by inventory or frontend analysis.
     * @throws IllegalArgumentException if a fold selection has an invalid shape or cannot be applied safely.
     * @throws IllegalStateException if selection or snapshot capture fails, including missing SPI capture.
     */
    fun analyze(
        artifact: Path, rules: List<FoldRule>, dependencies: List<Path> = emptyList(), dependencyResources: Boolean = false,
    ): ProgramSnapshot {
        val inventory = BytecodeInventory().also { it.read(artifact) }
        inventory.overrides = inventory.inheritedEntries.resolve(dependencies, inventory.diagnostics, inventory.methodDiagnostics)
        inventory.missingReferences(dependencies).forEach { inventory.diagnostics.add("Unresolved class reference: $it") }
        val resources = if (dependencyResources) ResourceDeclarations(listOf(artifact) + dependencies) else null
        return AnalysisInputs(artifact, dependencies).use { inputs ->
            analyzeInputs(inputs.path, rules, inventory, RuleAuditCapture(onAudit), resources)
        }
    }

    internal fun analyzeOriginProject(
        project: OriginProject, rules: List<FoldRule>, dependencyResources: Boolean = false,
    ): ProgramSnapshot {
        val compiled = project.compiled
        val dependencies = (compiled.dependencies +
            if (dependencyResources) compiled.production?.resourceDependencies.orEmpty() else emptyList()).distinct()
        if (project.owners.isEmpty()) return analyze(compiled.classes, rules, dependencies, dependencyResources)
        // Class names remain observable when anonymous owners were normalized for structural alignment.
        val inventory = BytecodeInventory(normalizedRuntimeNames = project.owners.any { it.key != it.value })
            .also { it.read(compiled.classes) }
        inventory.overrides = inventory.inheritedEntries.resolve(dependencies, inventory.diagnostics, inventory.methodDiagnostics)
        inventory.missingReferences(dependencies).forEach {
            inventory.diagnostics.add("Unresolved class reference: $it")
        }
        val audit = RuleAuditCapture(onAudit)
        val resources = if (dependencyResources) ResourceDeclarations(listOf(compiled.classes) + dependencies) else null
        val resolveSelection = { graph: Graph, rule: FoldSites, index: Int ->
            resolve(graph, rule) { audit.selected(index, it) }
        }
        val selected = if (rules.isEmpty()) emptyList() else {
            val original = BytecodeInventory().also { it.read(project.sourceClasses) }
            AnalysisInputs(project.sourceClasses, dependencies).use { inputs ->
                val graph = JavaProjectLoader(LoaderConfig(
                    interproceduralDataflow = true,
                    includePackages = if (dependencyResources) emptyList() else original.classes.toList(), includeLibraries = true,
                    verbose = { message ->
                        if (listOf("Skipping method", "Skipping control-flow", "Failed to", "Recovered ").any(message::contains)) {
                            inventory.diagnostics.add(message)
                        }
                    },
                ), false).load(inputs.path)
                originFoldSelection(graph, rules, project.owners, resolveSelection, audit)
            }
        }
        return AnalysisInputs(compiled.classes, dependencies).use { inputs ->
            analyzeInputs(inputs.path, selected, inventory, audit, resources, selectionResolved = true)
        }
    }

    private fun analyzeInputs(
        artifact: Path, rules: List<FoldRule>, inventory: BytecodeInventory, audit: RuleAuditCapture,
        resources: ResourceDeclarations? = null,
        selectionResolved: Boolean = false,
    ): ProgramSnapshot {
        val diagnostics = mutableListOf<String>()
        diagnostics.addAll(inventory.diagnostics)
        diagnostics.addAll(resources?.diagnostics.orEmpty())
        val log: (String) -> Unit = { message ->
            val incomplete = listOf("Skipping method", "Skipping control-flow", "Failed to", "Recovered ")
            if (incomplete.any(message::contains)) diagnostics.add(message)
        }
        val resolved = if (!selectionResolved && rules.isNotEmpty()) {
            selectInputs(artifact, rules, resources?.classes ?: inventory.classes, log, audit)
        } else rules
        val sink = SnapshotSink(inventory.ignoredFields, resources?.classes ?: inventory.classes,
            resources != null, resources?.trustedIntrinsics == true)
        var report: FoldReport? = null
        val config = LoaderConfig(
            folding = resolved.takeIf { it.isNotEmpty() }?.let { FoldPlan(it) { value -> report = value } },
            verbose = log,
            includePackages = if (resources != null) emptyList() else inventory.classes.toList(), includeLibraries = true,
            // Selection needs these edges independently; ordinary folded snapshots consume bodies and call sites only.
            interproceduralDataflow = resources != null,
        )
        val loader = JavaProjectLoader(config, false)
        val folded = SnapshotExtension.capture(sink) { loader.load(artifact) }
        val resourceAnalysis = resourceAnalysis(resources, inventory, sink, folded)
        val methods = foldedMethods(inventory, sink, folded, resourceAnalysis)
        if (loader.bodyInterceptionFallbackCount > 0) diagnostics.add("Frontend recovered method bodies without requested transforms")
        report?.outcomes?.forEachIndexed { index, outcome ->
            audit.applied(index, outcome.matched)
            outcome.unsupported.forEach { diagnostics.add("${it.method}: ${it.reason}") }
            outcome.hints.filter { it.contains("every caller") }.forEach(diagnostics::add)
        }
        check(sink.declarations.isNotEmpty()) { "Graphite SPI captured no classes from $artifact" }
        (inventory.methods - sink.methods.keys).forEach { diagnostics.add("Frontend omitted declared method: $it") }
        (resources?.methods.orEmpty() - sink.methods.keys).forEach { diagnostics.add("Frontend omitted resource dependency method: $it") }
        return ProgramSnapshot(methods, inventory.declarations, diagnostics.distinct(),
            report?.outcomes?.map { it.matched }.orEmpty(), inventory.fields, inventory.classShapes,
            inventory.ignoredFields, inventory.entryClasses, inventory.externalEntries,
            inventory.initialization.dependencies(), inventory.overrides, inventory.enclosingClasses, inventory.methodDiagnostics,
            resourceDependenciesAnalyzed = resources != null && diagnostics.isEmpty(),
            resourceEntryReads = resourceEntries(inventory.classes, resourceAnalysis))
    }

    private fun foldedMethods(
        inventory: BytecodeInventory, sink: SnapshotSink, folded: Graph, resourceAnalysis: DependencyResourceAnalysis?,
    ): Map<String, MethodSnapshot> {
        val dispatch = folded.nodes(CallSiteNode::class.java).groupBy { it.caller.identity }
        return sink.methods.filterKeys { it in inventory.methods }.mapValues { (identity, method) ->
            method.copy(calls = method.calls + dispatch[identity].orEmpty().map { it.callee.identity },
                resourceReads = resourceAnalysis?.reads(identity) ?: method.resourceReads)
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
        artifact: Path, rules: List<FoldRule>, owners: Set<String>, log: (String) -> Unit, audit: RuleAuditCapture,
    ): List<FoldRule> {
        val plain = JavaProjectLoader(LoaderConfig(
            interproceduralDataflow = true, verbose = log,
            includePackages = owners.toList(), includeLibraries = true,
        ), false).load(artifact)
        return rules.mapIndexed { index, rule ->
            runCatching {
                when (rule) {
                    is FoldSites -> resolve(plain, rule) { audit.selected(index, it) }
                    is ConstantFold -> rule.also { audit.selected(index, constantSelection(plain, it)) }
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
            val origin = bytecodeOrigin(graph, site) ?: continue
            val targets = leaves(origin)
            require(origin.id in selectedIds || targets.all { it.id in selectedIds }) {
                "Selecting only some targets of a shared functional call would broaden the fold"
            }
            val key = CallSiteKey(
                origin.caller.signature, origin.caller.descriptor, origin.callee.signature,
                origin.callee.descriptor, requireNotNull(origin.ordinal),
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

    private fun bytecodeOrigin(graph: Graph, site: CallSiteNode): CallSiteNode? {
        var origin = site
        val seen = mutableSetOf<NodeId>()
        while ((origin.ordinal ?: error("CallSite has no ordinal")) < 0) {
            require(seen.add(origin.id)) { "Cycle in derived call origins" }
            val parent = origin.origin ?: return null // A creation edge has no bytecode invocation.
            origin = graph.node(parent) as? CallSiteNode ?: error("Invalid derived call origin")
        }
        return origin
    }
}
