package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot

/** Only uniquely equivalent removed/added private members are paired. Public API names stay observable. */
internal class PrivateMemberAlignment(private val base: ProgramSnapshot, private val head: ProgramSnapshot) {
    val names = linkedMapOf<String, String>()

    init {
        val oldFields = base.fields.values.filter { it.renameSafe && it.identity !in head.fields }
        val newFields = head.fields.values.filter { it.renameSafe && it.identity !in base.fields }
        pair(oldFields.groupBy { listOf(owner(it.identity), it.declaration, it.constant) }
            .mapValues { (_, fields) -> fields.map { it.identity } },
            newFields.groupBy { listOf(owner(it.identity), it.declaration, it.constant) }
                .mapValues { (_, fields) -> fields.map { it.identity } })
        val oldMethods = base.methods.values.filter { "PRIVATE" in it.modifiers && it.identity !in head.methods }
            .associateBy { it.identity }.toMutableMap()
        val newMethods = head.methods.values.filter { "PRIVATE" in it.modifiers && it.identity !in base.methods }
            .associateBy { it.identity }.toMutableMap()
        // A round indexes signatures once. Stop at the fixed point; ambiguous cycles stay unpaired.
        while (oldMethods.isNotEmpty() && newMethods.isNotEmpty()) {
            val before = oldMethods.values.groupBy { method ->
                MethodKey(owner(method.identity), method.identity.substringAfter('('), base.declarations[method.identity],
                    method.referenceCanonical, method.references.map { if (it == method.identity) null else it })
            }.mapValues { (_, methods) -> methods.map { it.identity } }
            val after = newMethods.values.groupBy { method ->
                MethodKey(owner(method.identity), method.identity.substringAfter('('), head.declarations[method.identity],
                    method.referenceCanonical, method.references.map { if (it == method.identity) null else names[it] ?: it })
            }.mapValues { (_, methods) -> methods.map { it.identity } }
            val matched = pair(before, after)
            if (matched.isEmpty()) break
            matched.forEach { (new, old) -> oldMethods.remove(old); newMethods.remove(new) }
        }
    }

    private data class MethodKey(
        val owner: String, val descriptor: String, val declaration: String?, val canonical: String,
        val references: List<String?>,
    )

    fun aligned(): ProgramSnapshot = head.copy(
        methods = head.methods.values.associate { method ->
            val identity = names[method.identity] ?: method.identity
            identity to method.copy(identity = identity, calls = method.calls.map { names[it] ?: it }.toSet(),
                references = method.references.map { names[it] ?: it })
        },
        declarations = head.declarations.mapKeys { names[it.key] ?: it.key },
        fields = head.fields.values.associate { field ->
            val identity = names[field.identity] ?: field.identity
            identity to field.copy(identity = identity)
        },
        ignoredFields = head.ignoredFields.map { names[it] ?: it }.toSet(),
    )

    private fun <K> pair(old: Map<K, List<String>>, new: Map<K, List<String>>): Map<String, String> {
        val matched = linkedMapOf<String, String>()
        old.forEach { (key, before) ->
            val after = new[key].orEmpty()
            if (before.size == 1 && after.size == 1) matched[after.single()] = before.single()
        }
        names.putAll(matched)
        return matched
    }

    private fun owner(identity: String): String = identity.substringBefore('(').substringBefore(':').substringBeforeLast('.')
}
