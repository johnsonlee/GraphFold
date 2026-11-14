package io.johnsonlee.graphfold.compiler.kotlin

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.LocalVariableNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

/** Reconciles compiler symbol calls with final emitted debug scopes; ambiguous evidence cannot authorize a fold. */
internal fun inlineEvidence(
    methods: List<InlineMethodCapture>, conflicting: Set<String>, outputs: Map<String, Path>, emittedOwners: Set<String>,
): String {
    val declarations = methods.filter { it.inline }.mapNotNull { declaration ->
        declaration.source?.let { InlineDeclaration(declaration, it) }
    }
    if (declarations.isEmpty()) return "{\"schema\":1,\"declarations\":[],\"copies\":[],\"classes\":[]}"
    val classes = outputs.map { (owner, path) -> runCatching { emittedInlineClass(owner, Files.readAllBytes(path)) }.getOrNull() }
    val emitted = classes.filterNotNull()
    val completeOutputs = classes.none { it == null } && emitted.map { it.owner }.containsAll(emittedOwners)
    val copies = mutableListOf<InlineCopy>()
    val records = declarations.map { (declaration, source) ->
        val unique = declarations.count { it.capture.name == declaration.name } == 1 &&
            declarations.count { it.source == source } == 1
        val complete = completeOutputs && unique && declaration.safeMarkers && declaration.method !in conflicting &&
            reconcileCopies(declaration, methods, conflicting, emitted, copies)
        "{\"method\":${originJson(declaration.method)},\"source\":${originJson(source.source)}," +
            "\"sourceHash\":${originJson(source.hash)},\"start\":${source.start},\"end\":${source.end},\"complete\":$complete}"
    }
    val identities = emitted.map { "{\"owner\":${originJson(it.owner)},\"classHash\":${originJson(it.hash)}}" }
    return "{\"schema\":1,\"declarations\":[${records.joinToString(",")}],\"copies\":[${copies.joinToString(",") { it.json() }}]," +
        "\"classes\":[${identities.joinToString(",")}] }"
}

private fun reconcileCopies(
    declaration: InlineMethodCapture, methods: List<InlineMethodCapture>, conflicting: Set<String>,
    classes: List<InlineClass>, copies: MutableList<InlineCopy>,
): Boolean {
    val callers = methods.associateBy { it.method }
    val expected = methods.associate { it.method to it.targets.count { target -> target == declaration.source } }.filterValues { it > 0 }
    val matched = mutableMapOf<String, Int>()
    var complete = classes.any { it.methods.containsKey(declaration.method) }
    val emittedCallers = classes.flatMap { emitted -> emitted.methods.filterKeys { it != declaration.method }.map { (id, body) ->
        InlineCaller(id, body, emitted.hash)
    } }
    emittedCallers.forEach { caller ->
        val scopes = inspectScopes(caller.body, INLINE_MARKER_PREFIX + declaration.name, callers[caller.id],
            expected[caller.id], caller.id !in conflicting)
        complete = complete && scopes.complete
        if (scopes.count > 0) matched[caller.id] = scopes.count
        scopes.ranges.forEach { (start, end) -> copies += InlineCopy(declaration.method, caller.id, start, end, caller.hash) }
    }
    return complete && matched == expected
}

private fun inspectScopes(
    method: MethodNode, marker: String, capture: InlineMethodCapture?, expected: Int?, consistent: Boolean,
): InlineScopes {
    val variables = method.localVariables.orEmpty()
    val scopes = variables.filter { it.name == marker }
    val ranges = scopes.map { opcodeRange(method, it) }
    // Newer/unknown compiler scope-name encodings are not silently treated as absent copies.
    val supported = variables.none { it.name.startsWith(marker + "\\") || it.name.startsWith(marker + "\$iv") }
    val captured = scopes.isEmpty() || capture != null && capture.safeMarkers && capture.targets.none { it == null } &&
        consistent && expected == scopes.size
    val complete = supported && captured && ranges.none { it == null } && ranges.distinct().size == ranges.size
    return InlineScopes(scopes.size, complete, ranges.filterNotNull())
}

private data class InlineDeclaration(val capture: InlineMethodCapture, val source: InlineSourceIdentity)
private data class InlineCaller(val id: String, val body: MethodNode, val hash: String)
private data class InlineScopes(val count: Int, val complete: Boolean, val ranges: List<Pair<Int, Int>>)

private fun opcodeRange(method: MethodNode, variable: LocalVariableNode): Pair<Int, Int>? {
    if (variable.desc != "I") return null
    var position = 0
    val positions = method.instructions.toArray().associateWith { node ->
        position.also { if (node.opcode >= 0) position++ }
    }
    val start = positions[variable.start]
    val end = positions[variable.end]
    // Opcode positions are monotone: positive width also excludes reversed, equal and metadata-only spans.
    return if (start != null && end != null && end > start) start to end else null
}

private fun emittedInlineClass(owner: String, bytes: ByteArray): InlineClass {
    val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
    require(node.name.replace('/', '.') == owner) { "Compiler output owner mismatch" }
    val methods = node.methods.associateBy { "$owner.${it.name}${it.desc}" }
    require(methods.size == node.methods.size) { "Duplicate emitted method" }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return InlineClass(owner, hash, methods)
}

private data class InlineClass(val owner: String, val hash: String, val methods: Map<String, MethodNode>)
private data class InlineCopy(val target: String, val caller: String, val start: Int, val end: Int, val hash: String) {
    fun json(): String = "{\"target\":${originJson(target)},\"caller\":${originJson(caller)},\"startOpcode\":$start," +
        "\"endOpcode\":$end,\"classHash\":${originJson(hash)}}"
}
