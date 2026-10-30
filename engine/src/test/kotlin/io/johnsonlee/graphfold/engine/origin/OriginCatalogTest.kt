package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginCatalogTest {
    @TempDir lateinit var temporary: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `actual Java origins distinguish same-line expressions after all debug attributes are removed`() {
        val fixture = compile()
        stripDebug(fixture.project.classes)
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(setOf("sample.Example${'$'}1", "sample.Example${'$'}2"), result.origins.map { it.owner }.toSet())
        assertEquals(2, result.origins.map { it.syntax.range }.toSet().size)
    }

    @Test
    fun `Kotlin emitted origin markers validate physical object expressions without debug tables`() {
        val fixture = compile(kotlin = true)
        stripDebug(fixture.project.classes)
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(2, result.origins.size)
        assertEquals(2, result.origins.map { it.syntax.range }.toSet().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Kotlin CRLF and optional BOM bind physical compiler ranges to source syntax`(bom: Boolean) {
        val fixture = compile(kotlin = true) { source ->
            (if (bom) "\uFEFF" else "") + "// 😀\tphysical UTF16 ranges\r\n" + source.replace("\n", "\r\n")
        }
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(2, result.origins.size)
        val text = Files.readString(fixture.source)
        result.origins.forEach { origin ->
            assertEquals("object {}", text.substring(origin.syntax.range.start, origin.syntax.range.end))
        }
    }

    @ParameterizedTest
    @EnumSource(Corruption::class)
    fun `invalid Java provenance blocks rather than guessing source ownership`(corruption: Corruption) {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = mapper.readTree(file.toFile()) as ObjectNode
        val record = document["records"][0] as ObjectNode
        val expected = corruptDocument(corruption, document) ?: corruptRecord(corruption, record, document, fixture)
        mapper.writeValue(file.toFile(), document)
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.any { expected in it }, result.diagnostics.toString())
    }

    private fun corruptDocument(corruption: Corruption, document: ObjectNode): String? = when (corruption) {
        Corruption.SCHEMA -> { document.put("schema", 99); "Unknown compiler origin schema" }
        Corruption.INCOMPLETE -> { document.put("complete", false); "manifest is incomplete" }
        Corruption.STALE -> { document.put("runId", "previous-build"); "Stale compiler origin runId" }
        Corruption.COMPILER -> { (document["producer"] as ObjectNode).put("compiler", "unknown"); "Unknown compiler origin producer" }
        Corruption.BACKEND -> { (document["producer"] as ObjectNode).put("backend", "JS"); "Unknown compiler origin producer" }
        Corruption.VERSION -> { (document["producer"] as ObjectNode).put("version", ""); "Unknown compiler origin producer" }
        Corruption.RECORDS -> { document.remove("records"); "Compiler origin records are missing" }
        Corruption.UNRESOLVED -> {
            document.putArray("unresolved").add("compiler could not preserve owner")
            "Unresolved compiler origin"
        }
        else -> null
    }

    private fun corruptRecord(corruption: Corruption, record: ObjectNode, document: ObjectNode, fixture: Fixture): String =
        when (corruption) {
            Corruption.OWNER -> { record.put("owner", "sample.DoesNotExist"); "owner was not emitted" }
            Corruption.HASH -> { record.put("sourceHash", "previous-content"); "source hash differs" }
            Corruption.MISSING_RANGE -> { record.remove("allocation"); "Missing compiler source range" }
            Corruption.NEGATIVE_RANGE -> { (record["allocation"] as ObjectNode).put("start", -1); "Invalid compiler source range" }
            Corruption.EMPTY_RANGE -> {
                (record["allocation"] as ObjectNode).put("end", record["allocation"]["start"].asInt())
                "Invalid compiler source range"
            }
            Corruption.LARGE_RANGE -> { (record["declaration"] as ObjectNode).put("end", Int.MAX_VALUE); "Invalid compiler source range" }
            Corruption.WRONG_EXPRESSION -> {
                (record["allocation"] as ObjectNode).put("start", 0).put("end", 1)
                "does not identify one anonymous source expression"
            }
            Corruption.DUPLICATE -> {
                (document["records"] as com.fasterxml.jackson.databind.node.ArrayNode).add(record.deepCopy())
                "Duplicate compiler origin owner"
            }
            Corruption.OUTSIDE -> {
                val outside = temporary.resolve("Outside.java")
                Files.copy(fixture.source, outside)
                record.put("source", outside.toString())
                "source is outside checkout"
            }
            else -> error("Not a record mutation: $corruption")
        }

    @Test
    fun `missing empty and malformed manifest directories report actionable gaps`() {
        val fixture = compile()
        listOf(fixture.project.copy(originDirectory = null), fixture.project.copy(originRunId = null),
            fixture.project.copy(originRunId = ""), fixture.project.copy(originDirectory = temporary.resolve("missing"))).forEach {
            assertTrue(readOrigins(it, fixture.root).diagnostics.single().contains("manifest is missing"))
        }
        Files.delete(manifest(fixture.project))
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "manifest is empty" in it })
        Files.writeString(requireNotNull(fixture.project.originDirectory).resolve("invalid.json"), "not-json")
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "invalid.json" in it })
    }

    @Test
    fun `Kotlin marker removal and stale marker bytes cannot validate sidecar claims`() {
        val fixture = compile(kotlin = true)
        val classes = Files.walk(fixture.project.classes).use { it.filter { file -> file.toString().endsWith(".class") }.toList() }
        classes.forEach { file ->
            val node = ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            node.invisibleAnnotations?.removeIf { it.desc == ORIGIN_MARKER }
            node.visibleAnnotations?.removeIf { it.desc == ORIGIN_MARKER }
            val writer = ClassWriter(0)
            node.accept(writer)
            Files.write(file, writer.toByteArray())
        }
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.any { "marker missing" in it }, result.diagnostics.toString())
        assertTrue(result.origins.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(MarkerField::class)
    fun `Kotlin class markers must agree with sidecar identity and range fields`(field: MarkerField) {
        val fixture = compile(kotlin = true)
        val file = Files.walk(fixture.project.classes).use { paths ->
            paths.filter { it.toString().endsWith(".class") && it.fileName.toString().contains('$') }.findFirst().orElseThrow()
        }
        val node = ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        val marker = (node.invisibleAnnotations.orEmpty() + node.visibleAnnotations.orEmpty()).single { it.desc == ORIGIN_MARKER }
        val index = marker.values.indexOf(field.key) + 1
        check(index > 0)
        marker.values[index] = if (field in setOf(MarkerField.SCHEMA, MarkerField.START, MarkerField.END)) -1 else "stale"
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(file, writer.toByteArray())
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Kotlin origin marker differs" in it })
    }

    @Test
    fun `a named emitted Java owner cannot be substituted for an anonymous allocation`() {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = mapper.readTree(file.toFile()) as ObjectNode
        (document["records"][0] as ObjectNode).put("owner", "sample.Example")
        mapper.writeValue(file.toFile(), document)
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "not an anonymous class" in it })
    }

    @Test
    fun `noninteger end offsets cannot be coerced into an accepted compiler span`() {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = mapper.readTree(file.toFile()) as ObjectNode
        (document["records"][0]["allocation"] as ObjectNode).put("end", "42")
        mapper.writeValue(file.toFile(), document)
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Missing compiler source range" in it })
    }

    @Test
    fun `unknown Kotlin origin kinds block even when emitted marker and manifest agree`() {
        val fixture = compile(kotlin = true)
        val file = manifest(fixture.project)
        val document = mapper.readTree(file.toFile()) as ObjectNode
        val record = document["records"][0] as ObjectNode
        record.put("originKind", "FUTURE_ORIGIN")
        mapper.writeValue(file.toFile(), document)
        val artifact = fixture.project.classes.resolve(record["owner"].asText().replace('.', '/') + ".class")
        val node = ClassNode().also { ClassReader(Files.readAllBytes(artifact)).accept(it, 0) }
        val marker = node.invisibleAnnotations.single { it.desc == ORIGIN_MARKER }
        marker.values[marker.values.indexOf("originKind") + 1] = "FUTURE_ORIGIN"
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(artifact, writer.toByteArray())
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Unknown origin kind" in it })
    }

    private fun compile(kotlin: Boolean = false, format: (String) -> String = { it }): Fixture {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val source = root.resolve(if (kotlin) "src/main/kotlin/sample/Example.kt" else "src/main/java/sample/Example.java")
        Files.createDirectories(source.parent)
        val text = if (kotlin) {
            "package sample\nclass Example { fun first(): Any = object {}; fun second(): Any = object {} }"
        } else {
            "package sample; public class Example { Object first = new Object() {}; Object second = new Object() {}; }"
        }
        Files.writeString(source, format(text))
        return Fixture(root, source, ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("output")))
    }

    private fun manifest(project: CompiledProject): Path = Files.list(requireNotNull(project.originDirectory)).use {
        it.filter { file -> file.toString().endsWith(".json") }.toList().single()
    }

    private fun stripDebug(classes: Path) {
        Files.walk(classes).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.forEach { file ->
                val writer = ClassWriter(0)
                ClassReader(Files.readAllBytes(file)).accept(writer, ClassReader.SKIP_DEBUG)
                Files.write(file, writer.toByteArray())
            }
        }
    }

    enum class Corruption {
        SCHEMA, INCOMPLETE, STALE, COMPILER, BACKEND, VERSION, RECORDS, OWNER, HASH, MISSING_RANGE,
        NEGATIVE_RANGE, EMPTY_RANGE, LARGE_RANGE, WRONG_EXPRESSION, DUPLICATE, OUTSIDE, UNRESOLVED,
    }

    enum class MarkerField(val key: String) {
        SCHEMA("schema"), RUN_ID("runId"), SOURCE_HASH("sourceHash"), SOURCE("source"),
        KIND("originKind"), START("start"), END("end"),
    }

    private data class Fixture(val root: Path, val source: Path, val project: CompiledProject)
}
