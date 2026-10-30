package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot

/** Only uniquely equivalent removed/added private members are paired. Public API names stay observable. */
internal class PrivateMemberAlignment(private val base: ProgramSnapshot, private val head: ProgramSnapshot) {
    val names = linkedMapOf<String, String>()

    init {
        val oldFields = base.fields.values.filter { it.renameSafe && it.identity !in head.fields }
        val newFields = head.fields.values.filter { it.renameSafe && it.identity !in base.fields }
        pair(oldFields.map { it.identity }, newFields.map { it.identity }) { old, new ->
            val before = base.fields.getValue(old)
            val after = head.fields.getValue(new)
            owner(old) == owner(new) && before.declaration == after.declaration && before.constant == after.constant
        }
        val oldMethods = base.methods.values.filter { "PRIVATE" in it.modifiers && it.identity !in head.methods }
        val newMethods = head.methods.values.filter { "PRIVATE" in it.modifiers && it.identity !in base.methods }
        // Iteration admits chains of renamed helpers without guessing an arbitrary mapping for ambiguous cycles.
        repeat(oldMethods.size) {
            pair(oldMethods.map { it.identity }, newMethods.map { it.identity }) { old, new ->
                val before = base.methods.getValue(old)
                val after = head.methods.getValue(new)
                val mapping = names + (new to old)
                owner(old) == owner(new) && old.substringAfter('(') == new.substringAfter('(') &&
                    base.declarations[old] == head.declarations[new] &&
                    before.referenceCanonical == after.referenceCanonical &&
                    before.references == after.references.map { mapping[it] ?: it }
            }
        }
    }

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

    private fun pair(old: List<String>, new: List<String>, equivalent: (String, String) -> Boolean) {
        val candidates = old.filterNot { it in names.values }.associateWith { before ->
            new.filterNot { it in names }.filter { equivalent(before, it) }
        }
        candidates.forEach { (before, matches) ->
            if (matches.size == 1 && candidates.values.count { matches.single() in it } == 1) {
                names[matches.single()] = before
            }
        }
    }

    private fun owner(identity: String): String = identity.substringBefore('(').substringBefore(':').substringBeforeLast('.')
}
