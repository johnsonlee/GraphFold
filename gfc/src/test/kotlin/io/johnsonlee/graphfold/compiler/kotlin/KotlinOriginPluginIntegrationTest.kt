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
            val node = ClassNode().also { ClassReader(Files.readAllBytes(bytecode)).accept(it, 0) }
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
