package io.johnsonlee.graphfold.engine.origin.kotlin

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The invisible marker survives backend inline copying. The engine removes it from analysis artifacts. */
internal class KotlinOriginCollector(outputDirectory: Path, private val version: String, private val frontend: String,
    private val runId: String) {
    private val output = outputDirectory.resolve("kotlin-${java.util.UUID.randomUUID()}.json")
    private val emitted = linkedMapOf<String, Emission>()
    private val failures = linkedMapOf<String, String>()
    private val sources = linkedMapOf<String, Source>()
    private val outputs = linkedMapOf<String, String>()

    fun output(owner: String, sourceFiles: List<Path>) {
        val inputs = sourceFiles.distinct().map { file ->
            val path = file.toRealPath().toString()
            val source = requireNotNull(readSource(path)) { "Compiler output source is unreadable: $path" }
            "{\"path\":${json(path)},\"hash\":${json(source.hash)}}"
        }
        outputs[owner] = "{\"owner\":${json(owner)},\"sources\":[${inputs.joinToString(",")}] }"
    }

    fun start() {
        writeDocument(emptyList(), emptyList(), complete = false)
    }

    fun capture(owner: String, source: String, start: Int, end: Int, kind: String): Origin? {
        val path = try {
            Path.of(source).toRealPath().toString()
        } catch (_: IOException) {
            Path.of(source).toAbsolutePath().normalize().toString()
        }
        val contents = readSource(path)
        if (contents == null || !contents.validCompilerRange(start, end)) {
            failures[owner] = "Compiler IR has no verifiable physical source range"
            return null
        }
        return Origin(path, contents.hash, contents.compilerOffsets[start], contents.compilerOffsets[end], kind)
    }

    fun emitted(owner: String, origin: Origin?, missingIr: Boolean, anonymous: Boolean) {
        check(owner !in emitted) { "Duplicate emitted class: $owner" }
        emitted[owner] = Emission(owner, origin, if (origin == null) "INLINE_COPY" else "DIRECT_IR",
            markers = if (origin == null) 0 else 1, missingIr = missingIr, anonymous = anonymous)
    }

    fun annotation(owner: String, values: Map<String, Any?>) {
        val emission = emitted.getValue(owner)
        emission.markers++
        emission.origin = decode(values)
    }

    fun enclosing(owner: String, enclosingOwner: String, method: String?, descriptor: String?) {
        emitted.getValue(owner).apply {
            outerOwner = enclosingOwner
            outerMethod = method
            outerDescriptor = descriptor
        }
    }

    fun completed(owner: String) { emitted.getValue(owner).complete = true }

    fun finish() {
        val records = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        emitted.values.sortedBy { it.owner }.forEach { emission ->
            val origin = emission.origin
            if (emission.ignorable()) return@forEach
            if (origin == null || emission.markers != 1 || !emission.complete) {
                unresolved += "{\"owner\":${json(emission.owner.replace('/', '.'))},\"reason\":" +
                    json(failures[emission.owner] ?: "Emitted class has no unique verified compiler provenance") + "}"
            } else records += record(emission, origin)
        }
        writeDocument(records, unresolved, complete = true)
    }

    private fun writeDocument(records: List<String>, unresolved: List<String>, complete: Boolean) {
        val document = "{\"schema\":1,\"runId\":${json(runId)},\"producer\":{\"compiler\":\"kotlinc\",\"version\":${json(version)}," +
            "\"frontend\":${json(frontend)},\"backend\":\"JVM\"},\"complete\":$complete," +
            "\"records\":[${records.joinToString(",")}],\"unresolved\":[${unresolved.joinToString(",")}]," +
            "\"outputs\":[${outputs.values.joinToString(",")}] }"
        val target = output.toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "graphfold-origin-", ".json")
        try {
            Files.writeString(temporary, document)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun record(emission: Emission, origin: Origin): String {
        val span = "{\"start\":${origin.start},\"end\":${origin.end}}"
        return "{\"owner\":${json(emission.owner.replace('/', '.'))},\"source\":${json(origin.source)}," +
            "\"sourceHash\":${json(origin.sourceHash)},\"declaration\":$span,\"allocation\":$span," +
            "\"originKind\":${json(origin.kind)},\"provenance\":${json(emission.kind)}," +
            "\"enclosingOwner\":${json(emission.outerOwner?.replace('/', '.'))}," +
            "\"enclosingMethod\":${json(emission.outerMethod)},\"enclosingDescriptor\":${json(emission.outerDescriptor)}}"
    }

    private fun decode(values: Map<String, Any?>): Origin? {
        if (values["schema"] != 1 || values["runId"] != runId) return null
        val kind = values["originKind"] as? String
        val source = values["source"] as? String
        val hash = values["sourceHash"] as? String
        val start = values["start"] as? Int
        val end = values["end"] as? Int
        return when {
            kind == null || source == null || hash == null -> null
            start == null || end == null -> null
            kind !in setOf("ANONYMOUS_OBJECT", "DECLARATION") -> null
            else -> verifiedOrigin(source, hash, start, end, kind)
        }
    }

    private fun verifiedOrigin(source: String, hash: String, start: Int, end: Int, kind: String): Origin? {
        val contents = readSource(source) ?: return null
        return if (!validRange(contents, start, end) || contents.hash != hash) null
        else Origin(source, hash, start, end, kind)
    }

    private fun Source.validCompilerRange(start: Int, end: Int): Boolean =
        start >= 0 && end >= start && end < compilerOffsets.size

    private fun validRange(source: Source, start: Int, end: Int): Boolean =
        start >= 0 && end in start..source.text.length

    private fun Emission.ignorable(): Boolean {
        val needsOrigin = missingIr || anonymous
        return origin == null && markers == 0 && !needsOrigin
    }

    private fun readSource(path: String): Source? = sources[path] ?: try {
        val bytes = Files.readAllBytes(Path.of(path))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        Source(text, hash, compilerOffsets(text)).also { sources[path] = it }
    } catch (_: IOException) {
        null // The final sidecar retains an explicit unresolved record for unreadable compiler source.
    }

    internal data class Origin(val source: String, val sourceHash: String, val start: Int, val end: Int, val kind: String)
    private data class Emission(
        val owner: String, var origin: Origin?, val kind: String, var markers: Int, val missingIr: Boolean, val anonymous: Boolean,
        var complete: Boolean = false, var outerOwner: String? = null, var outerMethod: String? = null,
        var outerDescriptor: String? = null,
    )
    /** Kotlin strips an initial BOM and collapses CRLF; both coordinate systems otherwise count UTF-16 units. */
    private fun compilerOffsets(text: String): IntArray {
        val offsets = IntArray(text.length + 1)
        var physical = if (text.startsWith('\uFEFF')) 1 else 0
        offsets[0] = physical
        var normalized = 0
        while (physical < text.length) {
            if (text[physical] == '\r' && physical + 1 < text.length && text[physical + 1] == '\n') physical++
            physical++
            normalized++
            offsets[normalized] = physical
        }
        return offsets.copyOf(normalized + 1)
    }

    private data class Source(val text: String, val hash: String, val compilerOffsets: IntArray)

    companion object {
        const val MARKER: String = "Lio/johnsonlee/graphfold/engine/origin/SourceOrigin;"
    }
}

/** Compiler plugins cannot rely on the host build's Jackson version or classloader. */
private fun json(value: String?): String = if (value == null) "null" else buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < CONTROL_CHARACTER_LIMIT) {
                append("\\u" + character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
            } else append(character)
        }
    }
    append('"')
}

private const val CONTROL_CHARACTER_LIMIT = 32
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_WIDTH = 4
