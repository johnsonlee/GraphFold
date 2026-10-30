package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Path

/** Uses the same public frontend for selection and folded IR capture. */
class GraphiteAnalyzer {
    fun analyze(artifact: Path, rules: List<FoldRule>, dependencies: List<Path> = emptyList()): ProgramSnapshot {
        val inventory = BytecodeInventory().also { it.read(artifact) }
        inventory.overrides = inventory.inheritedEntries.resolve(dependencies, inventory.diagnostics)
        inventory.missingReferences(dependencies).forEach { inventory.diagnostics.add("Unresolved class reference: $it") }
        return AnalysisInputs(artifact, dependencies).use { inputs -> analyzeInputs(inputs.path, rules, inventory) }
    }

    internal fun analyzeOriginProject(project: OriginProject, rules: List<FoldRule>): ProgramSnapshot {
        val compiled = project.compiled
        if (project.owners.isEmpty()) return analyze(compiled.classes, rules, compiled.dependencies)
        val inventory = BytecodeInventory().also { it.read(compiled.classes) }
        inventory.overrides = inventory.inheritedEntries.resolve(compiled.dependencies, inventory.diagnostics)
        inventory.missingReferences(compiled.dependencies).forEach {
            inventory.diagnostics.add("Unresolved class reference: $it")
        }
        val selected = if (rules.isEmpty()) emptyList() else {
            val original = BytecodeInventory().also { it.read(project.sourceClasses) }
            AnalysisInputs(project.sourceClasses, compiled.dependencies).use { inputs ->
                val graph = JavaProjectLoader(LoaderConfig(
                    interproceduralDataflow = true, includePackages = original.classes.toList(), includeLibraries = true,
                    verbose = { message ->
                        if (listOf("Skipping method", "Skipping control-flow", "Failed to", "Recovered ").any(message::contains)) {
                            inventory.diagnostics.add(message)
                        }
                    },
                ), false).load(inputs.path)
                originFoldSelection(graph, rules, project.owners, ::resolve)
            }
        }
        return AnalysisInputs(compiled.classes, compiled.dependencies).use { inputs ->
            analyzeInputs(inputs.path, selected, inventory, selectionResolved = true)
        }
    }

    private fun analyzeInputs(
        artifact: Path, rules: List<FoldRule>, inventory: BytecodeInventory, selectionResolved: Boolean = false,
    ): ProgramSnapshot {
        val diagnostics = mutableListOf<String>()
        diagnostics.addAll(inventory.diagnostics)
        val log: (String) -> Unit = { message ->
            val incomplete = listOf("Skipping method", "Skipping control-flow", "Failed to", "Recovered ")
            if (incomplete.any(message::contains)) diagnostics.add(message)
        }
        val resolved = if (!selectionResolved && rules.any { it is FoldSites }) {
            val plain = JavaProjectLoader(LoaderConfig(
                interproceduralDataflow = true, verbose = log,
                includePackages = inventory.classes.toList(), includeLibraries = true,
            ), false).load(artifact)
            rules.map { if (it is FoldSites) resolve(plain, it) else it }
        } else rules
        val sink = SnapshotSink(inventory.ignoredFields)
        var report: FoldReport? = null
        val config = LoaderConfig(
            folding = resolved.takeIf { it.isNotEmpty() }?.let { FoldPlan(it) { value -> report = value } },
            verbose = log,
            includePackages = inventory.classes.toList(), includeLibraries = true,
        )
        val loader = JavaProjectLoader(config, false)
        val folded = SnapshotExtension.capture(sink) { loader.load(artifact) }
        val dispatch = folded.nodes(CallSiteNode::class.java).groupBy { it.caller.identity }
        val methods = sink.methods.filterKeys { it in inventory.methods }.mapValues { (identity, method) ->
            method.copy(calls = method.calls + dispatch[identity].orEmpty().map { it.callee.identity })
        }
        if (loader.bodyInterceptionFallbackCount > 0) diagnostics.add("Frontend recovered method bodies without requested transforms")
        report?.outcomes?.forEach { outcome ->
            outcome.unsupported.forEach { diagnostics.add("${it.method}: ${it.reason}") }
            outcome.hints.filter { it.contains("every caller") }.forEach(diagnostics::add)
        }
        check(sink.declarations.isNotEmpty()) { "Graphite SPI captured no classes from $artifact" }
        (inventory.methods - sink.methods.keys).forEach { diagnostics.add("Frontend omitted declared method: $it") }
        return ProgramSnapshot(methods, inventory.declarations, diagnostics.distinct(),
            report?.outcomes?.map { it.matched }.orEmpty(), inventory.fields, inventory.classShapes,
            inventory.ignoredFields, inventory.entryClasses, inventory.externalEntries,
            inventory.initialization.dependencies(), inventory.overrides, inventory.enclosingClasses)
    }

    internal fun resolve(graph: Graph, rule: FoldSites): FoldSites {
        val result = graph.query(rule.select)
        require(result.columns.size == 1) { "Fold select must return exactly one CallSite column" }
        val column = result.columns.single()
        val sites = result.rows.map { row ->
            val value = row[column] as? Map<*, *> ?: error("Fold select must return CallSite nodes")
            require(value["type"] == "CallSiteNode") { "Fold select returned a non-CallSite value" }
            val id = value["id"] as? Number ?: error("Fold select returned a node without an id")
            graph.node(NodeId(id.toInt())) as? CallSiteNode ?: error("Fold select returned an unknown CallSite")
        }
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
