package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.JsonNode
import io.johnsonlee.graphfold.engine.digest
import io.johnsonlee.graphfold.engine.inputs.InlineCopyEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineDeclarationEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineEvidence
import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** Optional copy evidence is usable only against the exact source and compiler-emitted class bytes. */
internal fun readInlineEvidence(document: JsonNode, classes: Path, checkout: Path): InlineEvidence {
    require(document.path("schema").asInt() == 1) { "Unknown inline evidence schema" }
    val names = listOf("classes", "declarations", "copies")
    require(names.all { document.path(it).isArray }) { "Incomplete inline evidence document" }
    val (hashes, nodes) = inlineClasses(document.path("classes"), classes)
    val methods = nodes.flatMap { (owner, node) -> node.methods.map { "$owner.${it.name}${it.desc}" to it } }.toMap()
    val declarations = inlineDeclarations(document.path("declarations"), methods.keys, checkout)
    val targets = declarations.map { it.method }.toSet()
    require(targets.size == declarations.size) { "Duplicate inline declarations" }
    val copies = inlineCopies(document.path("copies"), targets, methods, hashes)
    require(copies.map { listOf(it.caller, it.startOpcode, it.endOpcode) }.toSet().size == copies.size) {
        "Ambiguous duplicate inline copy intervals"
    }
    return InlineEvidence(declarations, copies, hashes)
}

private fun inlineClasses(entries: JsonNode, classes: Path): Pair<Map<String, String>, Map<String, ClassNode>> {
    val hashes = linkedMapOf<String, String>()
    val nodes = linkedMapOf<String, ClassNode>()
    entries.forEach { entry ->
        val owner = entry.path("owner").asText()
        require(owner.split('.').all { it.isNotBlank() } && '/' !in owner && '\\' !in owner) { "Invalid inline class owner" }
        val file = classes.resolve(owner.replace('.', '/') + ".class")
        val hash = entry.path("classHash").asText()
        require(Files.isRegularFile(file) && digest(file) == hash) { "Inline evidence class hash differs: $owner" }
        require(hashes.put(owner, hash) == null) { "Duplicate inline class: $owner" }
        val node = ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, ClassReader.SKIP_FRAMES) }
        require(node.name.replace('/', '.') == owner) { "Inline evidence class owner differs: $owner" }
        nodes[owner] = node
    }
    return hashes to nodes
}

private fun inlineDeclarations(
    entries: JsonNode, methods: Set<String>, checkout: Path,
): List<InlineDeclarationEvidence> =
    entries.map { entry ->
        val method = entry.path("method").asText()
        require(method in methods) { "Inline declaration was not emitted: $method" }
        val source = Path.of(entry.path("source").asText()).toRealPath()
        require(source.startsWith(checkout)) { "Inline source is outside checkout: $source" }
        val hash = entry.path("sourceHash").asText()
        require(digest(source) == hash) { "Inline source hash differs: $source" }
        require(entry.path("start").isInt && entry.path("end").isInt && entry.path("complete").isBoolean) {
            "Missing inline declaration coordinates or completeness: $method"
        }
        val start = entry.path("start").asInt()
        val end = entry.path("end").asInt()
        require(start >= 0 && end > start && end <= Files.readString(source).length) { "Invalid inline declaration span: $method" }
        InlineDeclarationEvidence(method, source, hash, start, end, entry.path("complete").asBoolean())
    }

private fun inlineCopies(
    entries: JsonNode, targets: Set<String>, methods: Map<String, MethodNode>, hashes: Map<String, String>,
): List<InlineCopyEvidence> =
    entries.map { entry ->
        val target = entry.path("target").asText()
        val caller = entry.path("caller").asText()
        require(target in targets && caller in methods) { "Inline copy has no emitted target or caller" }
        require(entry.path("startOpcode").isInt && entry.path("endOpcode").isInt) { "Missing inline copy interval" }
        val start = entry.path("startOpcode").asInt()
        val end = entry.path("endOpcode").asInt()
        require(start >= 0 && end > start && end <= methods.getValue(caller).instructions.count { it.opcode >= 0 }) {
            "Invalid inline copy interval: $caller"
        }
        val hash = entry.path("classHash").asText()
        require(hashes[caller.substringBefore('(').substringBeforeLast('.')] == hash) { "Inline copy class hash differs: $caller" }
        InlineCopyEvidence(target, caller, start, end, hash)
    }
