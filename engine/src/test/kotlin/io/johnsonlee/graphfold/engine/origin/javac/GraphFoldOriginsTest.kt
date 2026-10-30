package io.johnsonlee.graphfold.engine.origin.javac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.source.util.JavacTask
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphFoldOriginsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `actual compiler maps same-line initializers nested anonymous classes and enum bodies without debug tables`() {
        val source = """
            package sample;
            public class Example {
              static Object first = new Object() { int x = 1; }; Object field = new Object() { int y = 2; };
              static { Object inStatic = new Object() {}; }
              { Object inInstance = new Object() {}; }
              Object method() { Object a = new Object() {}; Object b = new Object() {};
                return new Object() { Object inner = new Object() {}; }; }
              class Nested { Object item = new Object() {}; }
              enum Choice { A { public int value() { return 1; } }, B { public int value() { return 2; } };
                public abstract int value(); }
            }
        """.trimIndent()
        val compilation = compile(source)
        assertTrue(compilation.success, compilation.diagnostics)
        val manifest = manifest(compilation.maps)
        assertEquals(1, manifest["schema"].asInt())
        assertEquals("javac", manifest["producer"]["compiler"].asText())
        assertEquals(System.getProperty("java.version"), manifest["producer"]["version"].asText())
        assertEquals("JVM", manifest["producer"]["backend"].asText())
        assertTrue(manifest["complete"].asBoolean())
        val records = manifest["records"].toList()
        assertEquals(11, records.size)
        assertEquals(11, records.map { it["owner"].asText() }.toSet().size)
        records.forEach { record ->
            val binary = record["owner"].asText()
            assertTrue(Files.isRegularFile(compilation.classes.resolve(binary.replace('.', '/') + ".class")), binary)
            assertEquals(compilation.source.toRealPath().toString(), record["source"].asText())
            assertEquals(hash(source), record["sourceHash"].asText())
            listOf("declaration", "allocation").forEach { range ->
                val start = record[range]["start"].asInt()
                val end = record[range]["end"].asInt()
                assertTrue(start >= 0 && end > start && end <= source.length, "$binary $range")
            }
        }
        val first = records.single { it["owner"].asText() == "sample.Example${'$'}1" }
        assertEquals(source.indexOf("{ int x = 1; }"), first["declaration"]["start"].asInt())
        assertEquals(source.indexOf("{ int x = 1; }") + "{ int x = 1; }".length, first["declaration"]["end"].asInt())
        val second = records.single { it["owner"].asText() == "sample.Example${'$'}2" }
        assertEquals(source.indexOf("{ int y = 2; }"), second["declaration"]["start"].asInt())
        assertTrue(records.any { it["owner"].asText() == "sample.Example${'$'}7${'$'}1" })
        assertTrue(records.any { it["owner"].asText() == "sample.Example${'$'}Choice${'$'}1" })
        assertTrue(records.any { it["owner"].asText() == "sample.Example${'$'}Choice${'$'}2" })
        verifyOutputInventory(compilation)
        val outputOwners = manifest["outputs"].map { it["owner"].asText() }.toSet()
        assertTrue(outputOwners.containsAll(setOf("sample.Example", "sample.Example${'$'}Nested", "sample.Example${'$'}Choice")))
        assertTrue(outputOwners.containsAll(records.map { it["owner"].asText() }))
    }

    @Test
    fun `plugin encoded paths preserve spaces UTF16 offsets and source hashes`() {
        val source = "// 😀 source\r\nclass Example { Object field = new Object() { int value = 1; }; }"
        val compilation = compile(source, plugin = true)
        assertTrue(compilation.success, compilation.diagnostics)
        val record = manifest(compilation.maps)["records"].single()
        val start = source.indexOf("{ int value = 1; }")
        assertEquals(start, record["declaration"]["start"].asInt())
        assertEquals(start + "{ int value = 1; }".length, record["declaration"]["end"].asInt())
        assertEquals(hash(source), record["sourceHash"].asText())
        assertTrue(record["source"].asText().contains("source with spaces"))
        verifyOutputInventory(compilation)
    }

    @Test
    fun `separate successful compilations cannot overwrite each others sidecars`() {
        val maps = Files.createDirectories(temporary.resolve("shared maps"))
        assertTrue(compile("class Example { Object x = new Object() {}; }", output = maps).success)
        assertTrue(compile("class Example { Object x = new Object() {}; }", output = maps).success)
        assertEquals(2L, Files.list(maps).use { it.filter { path -> path.toString().endsWith(".json") }.count() })
    }

    @Test
    fun `attributed but ungenerated anonymous classes do not produce complete sidecars`() {
        val result = compile("class Example { Object x = new Object() { int value() { return missing; } }; }")
        assertFalse(result.success)
        assertTrue(!Files.exists(result.maps) || Files.list(result.maps).use { it.findAny().isEmpty })
    }

    @Test
    fun `sources outside checkout cannot acquire trusted provenance`() {
        val root = Files.createDirectory(temporary.resolve("different-checkout"))
        val failure = assertFailsWith<RuntimeException> {
            compile("class Example { Object field = new Object() {}; }", options = Options(root = root))
        }
        assertTrue(failure.causes().contains("outside the supplied checkout root"), failure.causes())
    }

    @Test
    fun `non UTF8 compiler input is rejected rather than assigning wrong UTF16 ranges`() {
        val failure = assertFailsWith<RuntimeException> {
            compile("// café\nclass Example { Object field = new Object() {}; }", options = Options(charset = Charsets.ISO_8859_1))
        }
        assertTrue(failure.causes().contains("differs from UTF-8 file"), failure.causes())
    }

    @Test
    fun `missing source root fails before compilation`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            compile("class Example {}", options = Options(root = temporary.resolve("missing-root")))
        }
        assertTrue(failure.causes().contains("Source root is not accessible"), failure.causes())
    }

    @Test
    fun `unwritable manifest destination is a compilation failure`() {
        val output = temporary.resolve("occupied")
        Files.writeString(output, "preserve me")
        val failure = assertFailsWith<RuntimeException> {
            compile("class Example { Object field = new Object() {}; }", output = output)
        }
        assertTrue(failure.causes().contains("GraphFold javac source provenance failed"), failure.causes())
        assertEquals("preserve me", Files.readString(output))
    }

    @Test
    fun `malformed external plugin options fail before compilation`() {
        assertEquals("GraphFoldOrigins", GraphFoldOrigins().name)
        listOf(emptyList(), listOf("invalid-base64!", "also-invalid", "invalid")).forEach { arguments ->
            assertFailsWith<IllegalArgumentException> { compile("class Example {}", options = Options(pluginArguments = arguments)) }
        }
    }

    @Test
    fun `JSON provenance escapes all legal control and quote characters in physical paths and run IDs`() {
        val root = Files.createDirectory(temporary.resolve("quotes\" backslash\\ newline\n return\r tab\t control\u0001"))
        val runId = "run\"\\\n\r\t\u0002"
        val result = compile("class Example { Object field = new Object() {}; }", options = Options(sourceRoot = root, runId = runId))
        assertTrue(result.success, result.diagnostics)
        val document = manifest(result.maps)
        assertEquals(runId, document["runId"].asText())
        assertEquals(result.source.toRealPath().toString(), document["records"].single()["source"].asText())
        verifyOutputInventory(result)
    }

    private fun verifyOutputInventory(compilation: Compilation) {
        val actualOwners = Files.walk(compilation.classes).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.map {
                org.objectweb.asm.ClassReader(Files.readAllBytes(it)).className.replace('/', '.')
            }.toList().toSet()
        }
        val outputs = manifest(compilation.maps)["outputs"].toList()
        assertEquals(actualOwners.size, outputs.size, "Every emitted class must have exactly one ownership record")
        assertEquals(actualOwners, outputs.map { it["owner"].asText() }.toSet())
        val expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(compilation.source)))
        outputs.forEach { output ->
            val source = output["sources"].single()
            assertEquals(compilation.source.toRealPath().toString(), source["path"].asText())
            assertEquals(expectedHash, source["hash"].asText())
        }
    }

    private fun Throwable.causes(): String = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }

    private fun compile(
        source: String, plugin: Boolean = false, output: Path? = null, options: Options = Options(),
    ): Compilation {
        val directory = Files.createTempDirectory(options.sourceRoot ?: temporary, "source with spaces ")
        val file = directory.resolve("Example.java")
        Files.writeString(file, source, options.charset)
        val classes = Files.createDirectory(directory.resolve("classes"))
        val maps = output ?: directory.resolve("origin maps")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val compiler = ToolProvider.getSystemJavaCompiler()
        val success = compiler.getStandardFileManager(diagnostics, null, options.charset).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics,
                listOf("-g:none", "-proc:none", "--release", "17", "-d", classes.toString()), null,
                manager.getJavaFileObjectsFromPaths(listOf(file))) as JavacTask
            if (options.pluginArguments != null) {
                GraphFoldOrigins().init(task, *options.pluginArguments.toTypedArray())
            } else if (plugin) {
                fun encoded(path: Path) = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString().toByteArray())
                GraphFoldOrigins().init(task, encoded(maps), encoded(directory), encoded(Path.of("test-run")))
            } else if (options.runId != null) GraphFoldOrigins.attach(task, maps, options.root ?: directory, options.runId)
            else GraphFoldOrigins.attach(task, maps, options.root ?: directory)
            task.call()
        }
        return Compilation(file, classes, maps, success, diagnostics.diagnostics.joinToString("\n"))
    }

    private fun manifest(directory: Path): JsonNode {
        val file = Files.list(directory).use { it.filter { path -> path.toString().endsWith(".json") }.toList().single() }
        return jacksonObjectMapper().readTree(file.toFile())
    }

    private fun hash(source: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))

    private data class Options(
        val root: Path? = null, val charset: Charset = Charsets.UTF_8, val pluginArguments: List<String>? = null,
        val sourceRoot: Path? = null, val runId: String? = null,
    )

    private data class Compilation(val source: Path, val classes: Path, val maps: Path, val success: Boolean, val diagnostics: String)
}
