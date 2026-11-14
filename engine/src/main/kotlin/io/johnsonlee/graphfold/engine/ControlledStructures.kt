package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot

/** Every body needs positive same-input control removal, including initialization and unused private code. */
internal fun ownerRemovedByControls(snapshot: ProgramSnapshot, owner: String): Boolean {
    val methods = snapshot.methods.keys.filter { it.substringBefore('(').substringBeforeLast('.') == owner }
    if (owner !in snapshot.declarations) return ownerAbsent(snapshot, owner, methods)
    val covered = methods.isNotEmpty() && snapshot.controlRemovedMethods.containsAll(methods)
    val exposed = owner in snapshot.entryClasses.orEmpty() ||
        snapshot.externalEntries.any { it.substringBefore('(').substringBeforeLast('.') == owner }
    return covered && !exposed && !structuralObservation(snapshot, owner)
}

private fun ownerAbsent(snapshot: ProgramSnapshot, owner: String, methods: List<String>): Boolean {
    val inventoried = snapshot.classShapes.keys + snapshot.structuralClassShapes.keys + snapshot.initializationDependencies.keys
    return methods.isEmpty() && owner !in inventoried &&
        snapshot.fields.keys.none { it.substringBefore(':').substringBeforeLast('.') == owner }
}

/** Even uncalled signatures remain observations; absence from runtime reachability is never the proof. */
private fun structuralObservation(snapshot: ProgramSnapshot, owner: String): Boolean {
    fun mentions(value: String): Boolean = owner in value || owner.replace('.', '/') in value
    val surviving = snapshot.methods.values.filter { it.identity !in snapshot.controlRemovedMethods }
    val bodies = surviving.any {
        (listOf(it.identity, it.canonical) + it.references + listOfNotNull(snapshot.declarations[it.identity])).any(::mentions)
    }
    val fields = snapshot.fields.values.any { mentions(it.declaration) }
    val types = snapshot.structuralClassShapes.any { (other, shape) -> other != owner && mentions(shape.core) }
    val initialization = snapshot.initializationDependencies.any { (other, dependencies) -> other != owner && owner in dependencies }
    return listOf(bodies, fields, types, initialization).any { it }
}
