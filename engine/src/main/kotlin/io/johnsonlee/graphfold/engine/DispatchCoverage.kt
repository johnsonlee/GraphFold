package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot

/**
 * Proves that an omitted dispatch edge cannot hide a changed application implementation outside
 * the set supplied to [covers]. The caller must independently compare every method in that set, validate compiler
 * provenance, and reject private-member alignment or other normalization that erases differences.
 *
 * This is only a structural reachability argument. It neither proves external resource effects nor
 * repairs incomplete method bodies, recovery, or the graph used to select fold targets.
 */
internal class DispatchCoverage(base: ProgramSnapshot, head: ProgramSnapshot) {
    private val metadataComplete = base.diagnostics.isEmpty() && head.diagnostics.isEmpty() &&
        sameDispatchDeclarations(base, head) && sameDispatchEntries(base, head)
    private val changedMethods = (base.methods.keys + head.methods.keys).filterTo(linkedSetOf()) { identity ->
        val old = base.methods[identity]
        val new = head.methods[identity]
        old == null || new == null || old.canonical != new.canonical || base.declarations[identity] != head.declarations[identity]
    }

    /** Requires the caller's exact comparison set to cover every potentially hidden implementation change. */
    fun covers(compared: Set<String>): Boolean = metadataComplete && compared.containsAll(changedMethods)
}

private fun sameDispatchDeclarations(base: ProgramSnapshot, head: ProgramSnapshot): Boolean {
    val methods = base.methods.keys + head.methods.keys
    val classDeclarationsEqual = base.declarations.filterKeys { it !in methods } == head.declarations.filterKeys { it !in methods }
    return classDeclarationsEqual && base.fields == head.fields && base.classShapes == head.classShapes &&
        base.ignoredFields == head.ignoredFields && base.enclosingClasses == head.enclosingClasses &&
        base.originalOwners == head.originalOwners
}

private fun sameDispatchEntries(base: ProgramSnapshot, head: ProgramSnapshot): Boolean =
    base.entryClasses == head.entryClasses && base.externalEntries == head.externalEntries &&
        base.initializationDependencies == head.initializationDependencies && base.overrides == head.overrides &&
        base.initializationTriggerMethods == head.initializationTriggerMethods
