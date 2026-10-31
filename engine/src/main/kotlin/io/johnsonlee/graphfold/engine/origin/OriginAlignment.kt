package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.Revision
import java.security.MessageDigest

internal data class SourceOrigin(
    val owner: String, val path: String, val syntax: OriginSyntax,
    val emissionContext: String? = null, val enclosingOriginOwner: String? = null,
)
internal data class OriginAlignment(
    val baseOwners: Map<String, String>,
    val headOwners: Map<String, String>,
    val diagnostics: List<String>,
)

/** Pair compiler identities from unique physical-source contexts, never their generated ordinal. */
internal fun alignOrigins(
    base: List<SourceOrigin>, head: List<SourceOrigin>, renamedFiles: Map<String, String> = emptyMap(),
    reservedOwners: Set<String> = emptySet(),
): OriginAlignment = OriginPairing(base, head, renamedFiles, reservedOwners).align()

private class OriginPairing(
    private val base: List<SourceOrigin>,
    private val head: List<SourceOrigin>,
    private val renamedFiles: Map<String, String>,
    private val reservedOwners: Set<String>,
) {
    private val baseOwners = linkedMapOf<String, String>()
    private val headOwners = linkedMapOf<String, String>()
    private val diagnostics = linkedSetOf<String>()
    private val blockedBase = invalid(base, Revision.BASE)
    private val blockedHead = invalid(head, Revision.HEAD)
    private val remainingBase = base.filterNot { it.owner in blockedBase }.toMutableList()
    private val remainingHead = head.filterNot { it.owner in blockedHead }.toMutableList()

    fun align(): OriginAlignment {
        do {
            val previous = baseOwners.size
            match(eligible(remainingBase, base, baseOwners), eligible(remainingHead, head, headOwners), anchored = true)
            match(eligible(remainingBase, base, baseOwners), eligible(remainingHead, head, headOwners), anchored = false)
        } while (baseOwners.size > previous)
        ambiguousRemainders()
        assignUnmatched(base, baseOwners, Revision.BASE)
        assignUnmatched(head, headOwners, Revision.HEAD)
        return OriginAlignment(baseOwners, headOwners, diagnostics.toList())
    }

    private fun invalid(origins: List<SourceOrigin>, revision: Revision): Set<String> {
        val label = revision.name.lowercase()
        val blocked = mutableSetOf<String>()
        origins.groupBy { it.owner }.filterValues { it.size != 1 }.forEach { (owner, _) ->
            diagnostics += "$label has duplicate compiler provenance for $owner"
            blocked += owner
        }
        origins.groupBy { it.path to it.syntax.range }.values.filter(::ambiguousCopies).forEach { copies ->
            diagnostics += "$label has ambiguous emitted copies at one source origin: ${copies.map { it.owner }.sorted()}"
            blocked += copies.map { it.owner }
        }
        origins.filter { it.syntax.parentRange != null }.forEach { origin ->
            if (parent(origin, origins) == null) {
                diagnostics += "$label has no unique parent origin for ${origin.owner}"
                blocked += origin.owner
            }
        }
        return blocked
    }

    private fun ambiguousCopies(copies: List<SourceOrigin>): Boolean = copies.size > 1 &&
        (copies.any { it.emissionContext.isNullOrBlank() } || copies.map { it.emissionContext }.distinct().size != copies.size)

    private fun eligible(
        pending: List<SourceOrigin>, all: List<SourceOrigin>, aligned: Map<String, String>,
    ): List<SourceOrigin> = pending.filter { it.syntax.parentRange == null || parent(it, all)?.owner in aligned }

    private fun match(old: List<SourceOrigin>, new: List<SourceOrigin>, anchored: Boolean) {
        val oldGroups = old.groupBy { key(it, true, anchored) }
        val newGroups = new.groupBy { key(it, false, anchored) }
        oldGroups.forEach { (context, previous) ->
            val current = newGroups[context].orEmpty()
            if (anchored && previous.size == 1 && current.size == 1) pair(previous.single(), current.single())
            else matchUniqueBodies(previous, current)
        }
    }

    private fun matchUniqueBodies(previous: List<SourceOrigin>, current: List<SourceOrigin>) {
        val oldBodies = previous.groupBy { it.syntax.body }
        val newBodies = current.groupBy { it.syntax.body }
        oldBodies.forEach { (body, old) ->
            val new = newBodies[body].orEmpty()
            if (old.size == 1 && new.size == 1) pair(old.single(), new.single())
        }
    }

    private fun pair(old: SourceOrigin, new: SourceOrigin) {
        // Source evidence established this pair. The original base name is only its comparison label.
        val canonical = old.owner
        baseOwners[old.owner] = canonical
        headOwners[new.owner] = canonical
        remainingBase.remove(old)
        remainingHead.remove(new)
    }

    private fun key(origin: SourceOrigin, before: Boolean, anchored: Boolean): List<String> {
        val all = if (before) base else head
        val aligned = if (before) baseOwners else headOwners
        val path = if (before) renamedFiles[origin.path] ?: origin.path else origin.path
        val parent = parent(origin, all)?.let { aligned[it.owner] }.orEmpty()
        val context = listOf(path, parent, origin.syntax.container, origin.emissionContext.orEmpty())
        return context + if (anchored) listOf(origin.syntax.anchor) else emptyList()
    }

    private fun ambiguousRemainders() {
        val old = eligible(remainingBase, base, baseOwners)
        val new = eligible(remainingHead, head, headOwners)
        old.groupBy { key(it, true, true) }.forEach { (context, previous) ->
            val current = new.filter { key(it, false, true) == context }
            if (current.isNotEmpty()) diagnostics += "Ambiguous anonymous source context: " +
                "${previous.map { it.owner }.sorted()} versus ${current.map { it.owner }.sorted()}"
        }
        old.groupBy { key(it, true, false) to it.syntax.body }.forEach { (context, previous) ->
            val current = new.filter { key(it, false, false) to it.syntax.body == context }
            if (current.isNotEmpty()) diagnostics += "Ambiguous unchanged anonymous expressions: " +
                "${previous.map { it.owner }.sorted()} versus ${current.map { it.owner }.sorted()}"
        }
    }

    private fun assignUnmatched(origins: List<SourceOrigin>, aligned: MutableMap<String, String>, revision: Revision) {
        origins.sortedBy { it.syntax.range.start }.forEach { origin ->
            if (origin.owner !in aligned) aligned[origin.owner] = canonical(origin, origins, aligned, revision)
        }
    }

    private fun canonical(origin: SourceOrigin, all: List<SourceOrigin>, aligned: Map<String, String>, side: Revision): String {
        val parent = parent(origin, all)
        val prefix = parent?.let { aligned[it.owner] } ?: namedPrefix(origin)
        val identity = listOf(side.name.lowercase(), origin.path, origin.owner, origin.syntax.range.start, origin.syntax.range.end,
            origin.syntax.container, origin.syntax.anchor).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val result = "$prefix\$graphfold\$origin\$$hash"
        if (result in reservedOwners || result in baseOwners.values || result in headOwners.values) {
            diagnostics += "Synthetic origin comparison name collides with another class: $result"
        }
        return result
    }

    private fun namedPrefix(origin: SourceOrigin): String {
        val names = origin.syntax.container.split('/').filter { it.startsWith("type:") }.map { it.removePrefix("type:") }
        val packageName = origin.owner.substringBeforeLast('.', "")
        val simple = names.takeIf { it.isNotEmpty() }?.joinToString("$") ?: origin.owner.substringAfterLast('.').substringBefore('$')
        return if (packageName.isEmpty()) simple else "$packageName.$simple"
    }

    private fun parent(origin: SourceOrigin, all: List<SourceOrigin>): SourceOrigin? = origin.syntax.parentRange?.let { span ->
        all.filter { it.path == origin.path && it.syntax.range == span }.singleOrNull {
            origin.enclosingOriginOwner == null || it.owner == origin.enclosingOriginOwner
        }
    }
}
