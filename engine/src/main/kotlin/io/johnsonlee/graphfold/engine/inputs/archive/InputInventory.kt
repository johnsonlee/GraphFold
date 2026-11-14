package io.johnsonlee.graphfold.engine.inputs.archive

/** Revision-wide equality evidence preserves independently selected static input scopes. */
internal fun aggregateInputInventory(scopes: Map<String, InputProject>): ProductionInventory {
    val entries = linkedMapOf<String, String>()
    val nonClasses = linkedMapOf<String, String>()
    scopes.forEach { (id, project) ->
        val inventory = checkNotNull(project.production) { "Packaged input scope has no inventory: $id" }
        val prefix = "scope/${id.length}:$id/"
        entries[prefix] = "present"
        nonClasses[prefix] = "present"
        inventory.entries.forEach { (key, value) -> entries[prefix + key] = value }
        inventory.nonClassEntries.forEach { (key, value) -> nonClasses[prefix + key] = value }
    }
    return ProductionInventory(entries, nonClasses)
}
