package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler
import io.johnsonlee.graphfold.engine.inputs.archive.toStaticScope
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OriginCatalogTest {
    @TempDir lateinit var temporary: Path
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest
    @ValueSource(strings = ["opaque-provider-a", "opaque-provider-b"])
    fun `cached provenance binds exact class bytes and rejects tampering`(runId: String) {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        document.put("runId", runId)
        document["records"].forEach { record ->
            val binary = fixture.project.classes.resolve(record["owner"].asText().replace('.', '/') + ".class")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary))
                .joinToString("") { "%02x".format(it) }
            assertIs<ObjectNode>(record).put("classHash", hash)
        }
        mapper.writeValue(file.toFile(), document)
        val cached = fixture.project.copy(originRunId = runId, requireOriginClassHashes = true)
        assertTrue(readOrigins(cached, fixture.root).diagnostics.isEmpty())
        stripDebug(cached.classes)
        assertTrue(readOrigins(cached, fixture.root).diagnostics.any { "class hash differs" in it })
    }

    @Test fun `actual javac compiler records satisfy strict output hashes without manifest rewriting`() {
        val fixture = compile()
        val strict = fixture.project.copy(requireOriginClassHashes = true)
        val catalog = readOrigins(strict, fixture.root)
        assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
        assertTrue(catalog.origins.isNotEmpty())
        stripDebug(strict.classes)
        assertTrue(readOrigins(strict, fixture.root).diagnostics.any { "class hash differs" in it })
    }

    @Test
    fun `javac provenance cannot claim Kotlin inline copy evidence`() {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        assertEquals("javac", document.path("producer").path("compiler").asText())
        document.putObject("inlineEvidence").apply {
            put("schema", 1)
            putArray("classes"); putArray("declarations"); putArray("copies")
        }
        mapper.writeValue(file.toFile(), document)
        val catalog = readOrigins(fixture.project, fixture.root)
        assertTrue(catalog.diagnostics.any { "Inline evidence requires the Kotlin compiler" in it }, catalog.diagnostics.joinToString())
        assertTrue(catalog.inlineEvidence.isEmpty(), "Unsupported compiler evidence cannot authorize inline control proof")
    }

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
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        val record = assertIs<ObjectNode>(document["records"][0])
        val expected = corruptDocument(corruption, document) ?: corruptRecord(corruption, record, document, fixture)
        mapper.writeValue(file.toFile(), document)
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.any { expected in it }, result.diagnostics.toString())
    }

    private fun corruptDocument(corruption: Corruption, document: ObjectNode): String? = when (corruption) {
        Corruption.SCHEMA -> { document.put("schema", 99); "Unknown compiler origin schema" }
        Corruption.INCOMPLETE -> { document.put("complete", false); "manifest is incomplete" }
        Corruption.STALE -> { document.put("runId", "previous-build"); "Stale compiler origin runId" }
        Corruption.COMPILER -> { assertIs<ObjectNode>(document["producer"]).put("compiler", "unknown"); "Unknown compiler origin producer" }
        Corruption.BACKEND -> { assertIs<ObjectNode>(document["producer"]).put("backend", "JS"); "Unknown compiler origin producer" }
        Corruption.VERSION -> { assertIs<ObjectNode>(document["producer"]).put("version", ""); "Unknown compiler origin producer" }
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
            Corruption.NEGATIVE_RANGE -> { assertIs<ObjectNode>(record["allocation"]).put("start", -1); "Invalid compiler source range" }
            Corruption.EMPTY_RANGE -> {
                assertIs<ObjectNode>(record["allocation"]).put("end", record["allocation"]["start"].asInt())
                "Invalid compiler source range"
            }
            Corruption.LARGE_RANGE -> {
                assertIs<ObjectNode>(record["declaration"]).put("end", Int.MAX_VALUE)
                "Invalid compiler source range"
            }
            Corruption.WRONG_EXPRESSION -> {
                assertIs<ObjectNode>(record["allocation"]).put("start", 0).put("end", 1)
                "does not identify one anonymous source expression"
            }
            Corruption.DUPLICATE -> {
                assertIs<com.fasterxml.jackson.databind.node.ArrayNode>(document["records"]).add(record.deepCopy())
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Kotlin marker removal and stale marker bytes cannot validate sidecar claims`(removeAllAnnotations: Boolean) {
        val fixture = compile(kotlin = true)
        val classes = Files.walk(fixture.project.classes).use { it.filter { file -> file.toString().endsWith(".class") }.toList() }
        classes.forEach { file ->
            val node = ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            node.invisibleAnnotations?.removeIf { it.desc == ORIGIN_MARKER }
            node.visibleAnnotations?.removeIf { it.desc == ORIGIN_MARKER }
            if (removeAllAnnotations) {
                node.invisibleAnnotations = null
                node.visibleAnnotations = null
            }
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
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        assertIs<ObjectNode>(document["records"][0]).put("owner", "sample.Example")
        mapper.writeValue(file.toFile(), document)
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "not an anonymous class" in it })
    }

    @Test
    fun `noninteger end offsets cannot be coerced into an accepted compiler span`() {
        val fixture = compile()
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        assertIs<ObjectNode>(document["records"][0]["allocation"]).put("end", "42")
        mapper.writeValue(file.toFile(), document)
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Missing compiler source range" in it })
    }

    @Test
    fun `unknown Kotlin origin kinds block even when emitted marker and manifest agree`() {
        val fixture = compile(kotlin = true)
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        val record = assertIs<ObjectNode>(document["records"][0])
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

    @Test
    fun `Kotlin protocol rejects unknown or absent frontend and provenance labels`() {
        val fixture = compile(kotlin = true)
        val file = manifest(fixture.project)
        val original = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        listOf("FUTURE", "", null).forEach { invalid ->
            val frontend = original.deepCopy()
            val producer = assertIs<ObjectNode>(frontend["producer"])
            if (invalid == null) producer.remove("frontend") else producer.put("frontend", invalid)
            mapper.writeValue(file.toFile(), frontend)
            assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Unknown compiler origin frontend" in it })

            val provenance = original.deepCopy()
            val record = assertIs<ObjectNode>(provenance["records"][0])
            if (invalid == null) record.remove("provenance") else record.put("provenance", invalid)
            mapper.writeValue(file.toFile(), provenance)
            assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Unknown origin provenance" in it })
        }
        mapper.writeValue(file.toFile(), original)
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.isEmpty())
    }

    @Test
    fun `nested Java anonymous allocation retains its verified enclosing origin`() {
        val fixture = compile { """
            package sample;
            public class Example {
                Object outer() { return new Object() { Object nested = new Object() {}; }; }
            }
        """.trimIndent() }
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(2, result.origins.size)
        val outer = result.origins.single { it.enclosingOriginOwner == null }
        val nested = result.origins.single { it.enclosingOriginOwner != null }
        assertEquals("sample.Example.outer()Ljava/lang/Object;", outer.emissionContext)
        assertEquals(outer.owner, nested.enclosingOriginOwner)
        assertEquals(null, nested.emissionContext)
    }

    @Test
    fun `empty Kotlin marker never validates an otherwise matching manifest`() {
        val fixture = compile(kotlin = true)
        val file = manifest(fixture.project)
        val document = mapper.readTree(file.toFile())
        val artifact = fixture.project.classes.resolve(document["records"][0]["owner"].asText().replace('.', '/') + ".class")
        val node = ClassNode().also { ClassReader(Files.readAllBytes(artifact)).accept(it, 0) }
        node.invisibleAnnotations.single { it.desc == ORIGIN_MARKER }.values = null
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(artifact, writer.toByteArray())
        assertTrue(readOrigins(fixture.project, fixture.root).diagnostics.any { "Kotlin origin marker differs" in it })
    }

    @Test
    fun `omitted Kotlin metadata kind retains the annotation default class classification`() {
        val fixture = compile(kotlin = true)
        val files = Files.walk(fixture.project.classes).use { paths -> paths.filter { it.toString().endsWith(".class") }.toList() }
        files.forEach { artifact ->
            val node = ClassNode().also { ClassReader(Files.readAllBytes(artifact)).accept(it, 0) }
            val annotation = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
            annotation.values = annotation.values.chunked(2).filterNot { it.first() == "k" }.flatten().toMutableList()
            val writer = ClassWriter(0)
            node.accept(writer)
            Files.write(artifact, writer.toByteArray())
        }
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(2, result.origins.size)
    }

    @Test
    fun `manifest IO failure without a message remains a typed blocking diagnostic`() {
        val fixture = compile()
        val result = readOrigins(fixture.project, fixture.root) { throw java.io.IOException() }
        assertTrue(result.origins.isEmpty())
        assertTrue(result.diagnostics.any { it.endsWith(": IOException") }, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { "No verified compiler origin" in it })
    }

    @Test
    fun `absent enclosing metadata does not invent an enclosing origin owner`() {
        val fixture = compile()
        val file = manifest(fixture.project)
        val record = mapper.readTree(file.toFile())["records"][0]
        val artifact = fixture.project.classes.resolve(record["owner"].asText().replace('.', '/') + ".class")
        val node = ClassNode().also { ClassReader(Files.readAllBytes(artifact)).accept(it, 0) }
        node.outerClass = null
        node.outerMethod = null
        node.outerMethodDesc = null
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(artifact, writer.toByteArray())
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val origin = result.origins.single { it.owner == record["owner"].asText() }
        assertEquals(null, origin.enclosingOriginOwner)
        assertEquals("sample.Example.<init>()V", origin.emissionContext)
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
        return Fixture(root, source, SourceFixtureCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("output"))
            .toStaticScope(Files.createTempDirectory(temporary, "static-")))
    }

    private fun manifest(project: StaticScope): Path = Files.list(requireNotNull(project.originDirectory)).use {
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

    private data class Fixture(val root: Path, val source: Path, val project: StaticScope)
}
