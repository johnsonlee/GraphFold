package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Backend
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Compiler
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Kind
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.KotlinFrontend
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Provenance
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.TypeInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Provenance describes validated compiler artifacts, not a generated-name or line-table heuristic. */
internal data class OriginCatalog(
    val origins: List<SourceOrigin>, val diagnostics: List<String>, val compiledOwners: Set<String> = emptySet(),
)

internal fun readOrigins(project: CompiledProject, checkout: Path): OriginCatalog =
    OriginReader(project, checkout.toRealPath()).read()

private class OriginReader(private val project: CompiledProject, private val root: Path) {
    private val diagnostics = mutableListOf<String>()
    private val origins = linkedMapOf<String, SourceOrigin>()
    private val recorded = mutableSetOf<String>()
    private val sources = mutableMapOf<Path, Pair<String, List<OriginSyntax>>>()
    private val classes = Files.walk(project.classes).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.map { file ->
            ClassNode().apply { ClassReader(Files.readAllBytes(file)).accept(this, ClassReader.SKIP_FRAMES) }
        }.toList().associateBy { it.name.replace('/', '.') }
    }

    fun read(): OriginCatalog {
        val directory = project.originDirectory
        if (project.originRunId.isNullOrBlank() || directory == null || !Files.isDirectory(directory)) {
            return OriginCatalog(emptyList(), listOf("Compiler origin manifest is missing"))
        }
        val manifests = Files.list(directory).use { files -> files.filter { it.toString().endsWith(".json") }.sorted().toList() }
        if (manifests.isEmpty()) diagnostics += "Compiler origin manifest is empty"
        manifests.forEach { file -> guarded(file.toString()) { manifest(jacksonObjectMapper().readTree(file.toFile())) } }
        classes.filterValues(::anonymousObject).keys.filter { it !in origins }.forEach {
            diagnostics += "No verified compiler origin for anonymous class $it"
        }
        return OriginCatalog(withEmissionContexts(), diagnostics.toList(), classes.keys)
    }

    private fun withEmissionContexts(): List<SourceOrigin> {
        val contexts = mutableMapOf<String, MutableSet<String>>()
        val methods = classes.filterKeys { it !in origins }.flatMap { (owner, node) ->
            node.methods.filter { it.access and Opcodes.ACC_SYNTHETIC == 0 }.map { owner to it }
        }
        methods.forEach { (owner, method) ->
            val allocations = method.instructions.filterIsInstance<TypeInsnNode>()
                .filter { it.opcode == Opcodes.NEW }.map { it.desc.replace('/', '.') }.filter { it in origins }
            allocations.forEach { created ->
                contexts.getOrPut(created) { sortedSetOf() }.add("$owner.${method.name}${method.desc}")
            }
        }
        return origins.values.map { origin ->
            val node = classes.getValue(origin.owner)
            val enclosing = node.outerClass?.replace('/', '.')?.takeIf { it in origins }
            origin.copy(emissionContext = contexts[origin.owner]?.joinToString("\n"), enclosingOriginOwner = enclosing)
        }
    }

    private fun manifest(document: JsonNode) {
        require(document.path("schema").asInt() == 1) { "Unknown compiler origin schema" }
        require(document.path("complete").asBoolean()) { "Compiler origin manifest is incomplete" }
        require(document.path("runId").asText() == project.originRunId) { "Stale compiler origin runId" }
        val compiler = compiler(document.path("producer"))
        require(document.path("records").isArray) { "Compiler origin records are missing" }
        document.path("unresolved").forEach { diagnostics += "Unresolved compiler origin: $it" }
        document.path("records").forEach { record -> guarded(record.path("owner").asText()) { record(record, compiler) } }
    }

    private fun compiler(producer: JsonNode): Compiler {
        val compiler = requireNotNull(Compiler.entries.firstOrNull { it.wireValue() == producer.path("compiler").asText() }) {
            "Unknown compiler origin producer"
        }
        require(producer.path("backend").asText() == Backend.JVM.name &&
            producer.path("version").asText().isNotBlank()) { "Unknown compiler origin producer" }
        if (compiler == Compiler.KOTLINC) require(KotlinFrontend.entries.any { it.name == producer.path("frontend").asText() }) {
            "Unknown compiler origin frontend"
        }
        return compiler
    }

    private fun record(record: JsonNode, compiler: Compiler) {
        val owner = record.path("owner").asText()
        require(owner in classes) { "Compiler origin owner was not emitted: $owner" }
        if (project.originRunId.orEmpty().let { it.startsWith("gradle-v1-") || it.startsWith("maven-v1-") }) {
            val bytes = Files.readAllBytes(project.classes.resolve(owner.replace('.', '/') + ".class"))
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(record.path("classHash").asText() == hash) { "Compiler origin class hash differs: $owner" }
        }
        if (compiler == Compiler.JAVAC) require(anonymousObject(classes.getValue(owner))) {
            "Compiler origin owner is not an anonymous class: $owner"
        }
        require(recorded.add(owner)) { "Duplicate compiler origin owner: $owner" }
        val file = Path.of(record.path("source").asText()).toRealPath()
        require(file.startsWith(root)) { "Compiler origin source is outside checkout: $file" }
        val (text, syntax) = sources.getOrPut(file) {
            val text = Files.readString(file)
            text to originSyntax(file.toString(), text)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        require(record.path("sourceHash").asText() == hash) { "Compiler origin source hash differs: $file" }
        val allocation = span(record.path("allocation"), text.length)
        span(record.path("declaration"), text.length)
        if (compiler == Compiler.KOTLINC && kotlinKind(classes.getValue(owner), record) == Kind.DECLARATION) return
        val match = syntax.singleOrNull { it.range == allocation || allocation in it.aliases }
        requireNotNull(match) { "Compiler range does not identify one anonymous source expression: $file $allocation" }
        origins[owner] = SourceOrigin(owner, root.relativize(file).toString(), match)
    }

    private fun kotlinKind(node: ClassNode, record: JsonNode): Kind {
        verifyMarker(node, record)
        val kind = requireNotNull(Kind.entries.firstOrNull { it.name == record.path("originKind").asText() }) {
            "Unknown origin kind: ${record.path("originKind").asText()}"
        }
        require(Provenance.entries.any { it.name == record.path("provenance").asText() }) {
            "Unknown origin provenance: ${record.path("provenance").asText()}"
        }
        return kind
    }

    private fun span(node: JsonNode, length: Int): OriginSpan {
        require(node.path("start").isInt && node.path("end").isInt) { "Missing compiler source range" }
        val span = OriginSpan(node.path("start").asInt(), node.path("end").asInt())
        require(span.start >= 0 && span.end > span.start && span.end <= length) { "Invalid compiler source range: $span" }
        return span
    }

    private fun verifyMarker(node: ClassNode, record: JsonNode) {
        val marker = (node.invisibleAnnotations.orEmpty() + node.visibleAnnotations.orEmpty())
            .singleOrNull { it.desc == ORIGIN_MARKER }
        requireNotNull(marker) { "Kotlin compiler origin marker missing: ${node.name}" }
        val values = marker.values.orEmpty().chunked(2).associate { it[0].toString() to it[1] }
        require(values["schema"] == 1 && values["runId"] == project.originRunId &&
            values["sourceHash"] == record.path("sourceHash").asText() &&
            values["source"] == record.path("source").asText() &&
            values["originKind"] == record.path("originKind").asText() &&
            values["start"] == record.path("declaration").path("start").asInt() &&
            values["end"] == record.path("declaration").path("end").asInt()) { "Kotlin origin marker differs: ${node.name}" }
    }

    private fun anonymousObject(node: ClassNode): Boolean {
        if (node.innerClasses.none { it.name == node.name && it.innerName == null }) return false
        val metadata = node.visibleAnnotations.orEmpty().singleOrNull { it.desc == "Lkotlin/Metadata;" }
        val values = metadata?.values.orEmpty().chunked(2).associate { it[0].toString() to it[1] }
        // Kotlin lambdas and state machines use synthetic metadata, not anonymous object declarations.
        return metadata == null || (values["k"] ?: 1) == 1
    }

    @Suppress("TooGenericExceptionCaught") // Invalid or stale provenance must block, never silently remove an obligation.
    private fun guarded(context: String, action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            diagnostics += "$context: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }
}

internal const val ORIGIN_MARKER = "Lio/johnsonlee/graphfold/engine/origin/SourceOrigin;"
