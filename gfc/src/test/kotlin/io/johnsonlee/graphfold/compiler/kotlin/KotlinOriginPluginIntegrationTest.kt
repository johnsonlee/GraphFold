package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the packaged provider inside this test JVM so real compiler hooks also participate in JaCoCo. */
class KotlinOriginPluginIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["1.9", "2.1"])
    fun `real K1 and K2 compiler hooks preserve direct and inline object provenance`(language: String) {
        val factory = source("Factory.kt", """
            package sample
            inline fun create(crossinline value: () -> Int): java.util.function.IntSupplier =
                object : java.util.function.IntSupplier { override fun getAsInt(): Int = value() }
        """.trimIndent())
        val caller = source("Caller.kt", """
            package sample
            class Example {
                private val direct = object : java.util.function.IntSupplier { override fun getAsInt(): Int = 3 }
                fun value(): Int = create { 7 }.asInt + direct.asInt
            }
        """.trimIndent())
        val result = compile(listOf(factory, caller), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val documents = Files.list(temporary.resolve("origins")).use { files ->
            files.filter { it.toString().endsWith(".json") }.map {
                jacksonObjectMapper().readTree(it.toFile())
            }.toList()
        }
        val document = documents.single()
        assertTrue(document["complete"].asBoolean())
        assertEquals("integration-run", document["runId"].asText())
        assertEquals(if (language == "1.9") "K1" else "K2", document["producer"]["frontend"].asText())
        assertTrue(document["unresolved"].isEmpty, document.toString())
        val records = document["records"].toList()
        assertTrue(records.any { it["provenance"].asText() == "INLINE_COPY" }, records.toString())
        assertTrue(records.any { it["provenance"].asText() == "DIRECT_IR" && it["originKind"].asText() == "ANONYMOUS_OBJECT" })
        records.forEach { record ->
            val physical = Path.of(record["source"].asText())
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(physical)))
            assertEquals(hash, record["sourceHash"].asText())
            val start = record["declaration"]["start"].asInt()
            val end = record["declaration"]["end"].asInt()
            assertTrue(start >= 0 && end >= start && end <= Files.readString(physical).length)
            val bytecode = temporary.resolve("classes").resolve(record["owner"].asText().replace('.', '/') + ".class")
            val emittedBytes = Files.readAllBytes(bytecode)
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emittedBytes)),
                record["classHash"].asText())
            val node = ClassNode().also { ClassReader(emittedBytes).accept(it, 0) }
            assertTrue(node.invisibleAnnotations.orEmpty().any { it.desc == KotlinOriginCollector.MARKER })
        }
        val copied = records.filter { it["provenance"].asText() == "INLINE_COPY" }
        assertTrue(copied.all { Path.of(it["source"].asText()) == factory.toRealPath() }, copied.toString())
        URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("sample.Example")
            assertEquals(10, type.getMethod("value").invoke(type.getDeclaredConstructor().newInstance()))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.9", "2.1"])
    fun `lowered suspend lambda is not a source anonymous object`(language: String) {
        val source = source("Suspending.kt", """
            package sample
            import kotlin.coroutines.*
            class Example {
                fun run(x: Int): Int {
                    var result = 0
                    val action: suspend () -> Int = { x + 1 }
                    action.startCoroutine(object : Continuation<Int> {
                        override val context: CoroutineContext = EmptyCoroutineContext
                        override fun resumeWith(outcome: Result<Int>) { result = outcome.getOrThrow() }
                    })
                    return result
                }
            }
        """.trimIndent())
        val result = compile(listOf(source), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val document = Files.list(temporary.resolve("origins")).use { files ->
            jacksonObjectMapper().readTree(files.findFirst().orElseThrow().toFile())
        }
        assertTrue(document["complete"].asBoolean())
        assertTrue(document["unresolved"].isEmpty, document.toString())
        val nodes = Files.walk(temporary.resolve("classes")).use { files ->
            files.filter { it.toString().endsWith(".class") }.map { file ->
                ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            }.toList()
        }
        val lowered = nodes.single { it.superName == "kotlin/coroutines/jvm/internal/SuspendLambda" }
        assertTrue(lowered.methods.any { it.name == "invokeSuspend" })
        assertTrue(document["records"].none {
            it["owner"].asText() == lowered.name.replace('/', '.') && it["originKind"].asText() == "ANONYMOUS_OBJECT"
        })
        val continuation = document["records"].single { it["originKind"].asText() == "ANONYMOUS_OBJECT" }
        val start = continuation["allocation"]["start"].asInt()
        val end = continuation["allocation"]["end"].asInt()
        assertTrue(Files.readString(source).substring(start, end).startsWith("object : Continuation"))
        URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("sample.Example")
            assertEquals(5, type.getMethod("run", Int::class.javaPrimitiveType).invoke(type.getDeclaredConstructor().newInstance(), 4))
        }
    }

    @ParameterizedTest
    @CsvSource("1.9,false", "1.9,true", "2.1,false", "2.1,true")
    fun `BOM and CRLF source retain physical UTF16 offsets for same-line objects and inline copies`(language: String, bom: Boolean) {
        val prefix = if (bom) "\uFEFF" else ""
        val factoryText = prefix + """
            package sample
            // 😀 before source range
            inline fun create(crossinline value: () -> Int): java.util.function.IntSupplier =
                object : java.util.function.IntSupplier { override fun getAsInt(): Int = value() }
        """.trimIndent().replace("\n", "\r\n")
        val first = "object : java.util.function.IntSupplier { override fun getAsInt(): Int = 3 }"
        val second = "object : java.util.function.IntSupplier { override fun getAsInt(): Int = 4 }"
        val callerText = prefix + "package sample\r\n// 😀\t before objects\r\nclass Example {\r\n" +
            "\tval first = $first; val second = $second\r\n" +
            "\tfun value(): Int = create { 7 }.asInt + first.asInt + second.asInt\r\n}"
        val factory = source("Factory.kt", factoryText)
        val caller = source("Caller.kt", callerText)
        val result = compile(listOf(factory, caller), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val document = Files.list(temporary.resolve("origins")).use { files ->
            jacksonObjectMapper().readTree(files.filter { it.toString().endsWith(".json") }.toList().single().toFile())
        }
        assertTrue(document["unresolved"].isEmpty, document.toString())
        val objects = document["records"].filter { it["originKind"].asText() == "ANONYMOUS_OBJECT" }
        assertEquals(4, objects.size)
        val physicalSlices = objects.map { record ->
            val text = Files.readString(Path.of(record["source"].asText()))
            text.substring(record["declaration"]["start"].asInt(), record["declaration"]["end"].asInt())
        }
        assertEquals(1, physicalSlices.count { it == first })
        assertEquals(1, physicalSlices.count { it == second })
        val definition = "object : java.util.function.IntSupplier { override fun getAsInt(): Int = value() }"
        assertEquals(2, physicalSlices.count { it == definition })
        val copy = objects.single { it["provenance"].asText() == "INLINE_COPY" }
        assertEquals(factoryText.indexOf(definition), copy["declaration"]["start"].asInt())
        assertEquals(factoryText.indexOf(definition) + definition.length, copy["declaration"]["end"].asInt())
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.9", "2.1"])
    fun `callable reference implementations retain declaration provenance without masking actual objects`(language: String) {
        val fixture = requireNotNull(javaClass.getResource("/kotlin/callable-references/References.kt")).readText()
        val source = source("References.kt", fixture)
        val result = compile(listOf(source), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val document = Files.list(temporary.resolve("origins")).use { files ->
            jacksonObjectMapper().readTree(files.filter { it.toString().endsWith(".json") }.toList().single().toFile())
        }
        assertTrue(document["complete"].asBoolean())
        assertTrue(document["unresolved"].isEmpty, document.toString())
        val nodes = Files.walk(temporary.resolve("classes")).use { files ->
            files.filter { it.toString().endsWith(".class") }.map { file ->
                ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            }.toList()
        }
        val references = nodes.filter { it.superName.contains("Reference") }
        assertEquals(8, references.size, references.map { it.name }.toString())
        val slices = references.map { reference ->
            val record = document["records"].single { it["owner"].asText() == reference.name.replace('/', '.') }
            assertEquals("DECLARATION", record["originKind"].asText(), record.toString())
            assertEquals("DIRECT_IR", record["provenance"].asText())
            val marker = reference.invisibleAnnotations.single { it.desc == KotlinOriginCollector.MARKER }
            assertEquals("DECLARATION", marker.values.chunked(2).associate { it[0] to it[1] }["originKind"])
            fixture.substring(record["allocation"]["start"].asInt(), record["allocation"]["end"].asInt())
        }
        assertEquals(setOf("::top", "receiver::member", "Receiver::member", "Files::isRegularFile",
            "receiver::value", "Receiver::value", "receiver::mutable", "Receiver::mutable"), slices.toSet())
        val actualObject = document["records"].single { it["originKind"].asText() == "ANONYMOUS_OBJECT" }
        assertTrue(fixture.substring(actualObject["allocation"]["start"].asInt(),
            actualObject["allocation"]["end"].asInt()).startsWith("object : java.util.function.IntSupplier"))
        URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("sample.Example")
            assertEquals(38, type.getMethod("run").invoke(type.getDeclaredConstructor().newInstance()))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["output", "runId"])
    fun `missing required compiler options fail before accepting a provenance artifact`(omitted: String) {
        val result = compile(listOf(source("Example.kt", "class Example")), "2.1", omitted)
        assertNotEquals(ExitCode.OK, result.exit, result.log)
        assertTrue(omitted in result.log, result.log)
        assertTrue(!Files.exists(temporary.resolve("origins")))
    }

    @Test
    fun `unsupported plugin option fails compilation instead of silently disabling provenance`() {
        val result = compile(listOf(source("Example.kt", "class Example")), "2.1", extra = listOf(
            "-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:unknown=true",
        ))
        assertNotEquals(ExitCode.OK, result.exit, result.log)
        assertTrue("unknown" in result.log, result.log)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.9", "2.1"])
    fun `stdlib sortedBy generated comparator retains JVM identity without requiring external library IR`(language: String) {
        val source = source("Ordering.kt", """
            package sample
            class Ordering {
                fun order(values: List<String>): List<String> = values.sortedBy { it.length }
            }
        """.trimIndent())
        val result = compile(listOf(source), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val document = Files.list(temporary.resolve("origins")).use { files ->
            jacksonObjectMapper().readTree(files.filter { it.toString().endsWith(".json") }.toList().single().toFile())
        }
        assertTrue(document["complete"].asBoolean())
        assertTrue(document["unresolved"].isEmpty, document.toString())
        val nodes = Files.walk(temporary.resolve("classes")).use { files ->
            files.filter { it.toString().endsWith(".class") }.map { file ->
                ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            }.toList()
        }
        val comparator = nodes.single { "java/util/Comparator" in it.interfaces }
        assertEquals("sample/Ordering", comparator.outerClass)
        assertEquals("order", comparator.outerMethod)
        assertEquals("(Ljava/util/List;)Ljava/util/List;", comparator.outerMethodDesc)
        assertTrue(document["records"].none { it["owner"].asText() == comparator.name.replace('/', '.') })
        assertTrue(document["outputs"].any { it["owner"].asText() == comparator.name.replace('/', '.') }, document.toString())
        URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("sample.Ordering")
            assertEquals(listOf("a", "bb", "ccc"),
                type.getMethod("order", List::class.java).invoke(type.getDeclaredConstructor().newInstance(), listOf("ccc", "a", "bb")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.9", "2.1"])
    fun `inline copy evidence binds actual emitted methods source identities and instruction ranges`(language: String) {
        val physical = source("Inline.kt", """
            package sample
            class InlineExample {
                private inline fun helper(): Int = 7
                private inline val score: Int get() = 9
                private inline fun unused(): Int = 11
                fun run(enabled: Boolean): Int = if (enabled) helper() + score else 0
            }
        """.trimIndent())
        val result = compile(listOf(physical), language)
        assertEquals(ExitCode.OK, result.exit, result.log)
        val document = Files.list(temporary.resolve("origins")).use { paths ->
            jacksonObjectMapper().readTree(paths.filter { it.toString().endsWith(".json") }.toList().single().toFile())
        }
        val evidence = document["inlineEvidence"]
        val declarations = evidence["declarations"].associateBy { it["method"].asText() }
        assertEquals(setOf("sample.InlineExample.helper()I", "sample.InlineExample.getScore()I", "sample.InlineExample.unused()I"),
            declarations.keys, evidence.toString())
        assertTrue(declarations.values.all { it["complete"].asBoolean() }, evidence.toString())
        assertTrue(declarations.values.all { it["source"].asText() == physical.toRealPath().toString() })
        val bytes = Files.readAllBytes(temporary.resolve("classes/sample/InlineExample.class"))
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertEquals(setOf("sample.InlineExample.helper()I", "sample.InlineExample.getScore()I"),
            evidence["copies"].map { it["target"].asText() }.toSet(), evidence.toString())
        assertEquals(2, evidence["copies"].size())
        evidence["copies"].forEach { copy ->
            assertEquals("sample.InlineExample.run(Z)I", copy["caller"].asText())
            assertEquals(hash, copy["classHash"].asText())
            assertTrue(copy["startOpcode"].asInt() < copy["endOpcode"].asInt())
        }
        assertEquals(hash, evidence["classes"].single { it["owner"].asText() == "sample.InlineExample" }["classHash"].asText())
    }

    private fun source(name: String, contents: String): Path = temporary.resolve(name).also { Files.writeString(it, contents) }

    private fun compile(sources: List<Path>, language: String, omitted: String? = null, extra: List<String> = emptyList()): Result {
        val plugin = Path.of(requireNotNull(System.getProperty("graphfold.test.compilerPluginJar")))
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val libraries = listOf(Unit::class.java, org.jetbrains.annotations.NotNull::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        val options = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-language-version", language,
            "-classpath", libraries.joinToString(java.io.File.pathSeparator), "-d", classes.toString(), "-Xplugin=$plugin") +
            listOf("output" to temporary.resolve("origins").toString(), "runId" to "integration-run")
                .filter { it.first != omitted }.flatMap { (name, value) ->
                    listOf("-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:$name=$value")
                } + extra + sources.map(Path::toString)
        val log = ByteArrayOutputStream()
        val exit = PrintStream(log).use { K2JVMCompiler().exec(it, *options.toTypedArray()) }
        return Result(exit, log.toString())
    }

    private data class Result(val exit: ExitCode, val log: String)
}
