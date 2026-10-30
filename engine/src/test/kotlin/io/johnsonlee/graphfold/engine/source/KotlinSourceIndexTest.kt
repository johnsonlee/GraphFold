package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceRange
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSourceIndexTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `compiler emitted owners cover Kotlin declarations and generated callbacks`() {
        val source = """
            package example
            data class Example(val number: Int = 1) {
                var doubled: Int = number * 2
                companion object { fun create() = Example() }
                fun callback(): Runnable = object : Runnable { override fun run() { println(number) } }
                fun lambda(): () -> Int = { number + 1 }
                suspend fun delayed(value: Int = 2): Int = number + value
                fun local(): Int {
                    fun nested() = number + 3
                    return nested()
                }
            }
            object Singleton { val value = 4 }
            fun String.extensionValue(): Int = length
            val topLevel = 5
        """.trimIndent()
        val classes = compile(source)
        KotlinSourceIndex(classes).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val owners = result.symbols.map { it.owner }.toSet()
            assertTrue("example.Example" in owners)
            assertTrue("example.Example${'$'}Companion" in owners)
            assertTrue("example.Example${'$'}callback${'$'}1" in owners)
            assertTrue("example.Example${'$'}lambda${'$'}1" in owners)
            assertTrue("example.Singleton" in owners)
            assertTrue("example.ExampleKt" in owners)
            assertTrue(result.symbols.any { it.kind == SymbolKind.METHOD && it.identity == "example.Example.local()I" })
            assertTrue(result.symbols.any { it.kind == SymbolKind.METHOD && it.identity.startsWith("example.Example.delayed(") })
            assertTrue(result.symbols.any { it.kind == SymbolKind.FIELD && it.identity == "example.Example.doubled" })
            assertTrue(result.symbols.any { it.identity == "example.ExampleKt.extensionValue(Ljava/lang/String;)I" })
            assertTrue(ProofPlanner.plan(result, listOf(result.symbols.first().location)).size >= owners.size)
        }
    }

    @Test
    fun `file facade annotation and UTF16 coordinates preserve exact compiler ownership`() {
        val source = "@file:JvmName(\"CustomFacade\")\npackage example\nfun emoji() = \"😀\""
        KotlinSourceIndex(compile(source)).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.BASE)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val symbol = result.symbols.single { it.kind == SymbolKind.CLASS }
            assertEquals("example.CustomFacade", symbol.owner)
            assertEquals(Revision.BASE, symbol.location.revision)
            assertEquals(SourceRange(1, 3), symbol.location.line)
            assertEquals(SourceRange(1, source.substringAfterLast('\n').length + 1), symbol.location.column)
        }
    }

    @Test
    fun `actual compiler symbols retain physical locations with BOM CRLF tabs and astral text`() {
        val method = "fun emoji() = \"😀\""
        val source = "\uFEFFpackage example\r\nclass Example {\r\n\t$method\r\n}"
        KotlinSourceIndex(compile(source)).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertEquals(source, result.text)
            val symbol = result.symbols.single { it.identity == "example.Example.emoji()Ljava/lang/String;" }
            assertEquals(SymbolKind.METHOD, symbol.kind)
            assertEquals(SourceRange(3, 3), symbol.location.line)
            assertEquals(SourceRange(2, method.length + 2), symbol.location.column)
        }
    }

    @Test
    fun `syntax errors missing owners and stripped source metadata are blocking`() {
        val classes = Files.createDirectory(temporary.resolve("classes"))
        Files.writeString(temporary.resolve("Example.kt"), "class Example {")
        KotlinSourceIndex(classes).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isNotEmpty())
            assertTrue(result.symbols.isEmpty())
        }
        Files.writeString(temporary.resolve("Example.kt"), "class Example")
        KotlinSourceIndex(classes).use { index ->
            assertTrue(index.index(temporary, "Example.kt", Revision.HEAD).diagnostics.single().contains("No emitted"))
        }
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Example", null, "java/lang/Object", null)
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        writer.visitEnd()
        Files.write(classes.resolve("Example.class"), writer.toByteArray())
        KotlinSourceIndex(classes).use { index ->
            assertTrue(index.index(temporary, "Example.kt", Revision.HEAD).diagnostics.any { it.contains("without SourceFile") })
        }
    }

    @Test
    fun `identical filenames from different packages cannot select each others classes`() {
        val classes = compile("package original\nclass Example")
        Files.writeString(temporary.resolve("Example.kt"), "package changed\nclass Example")
        KotlinSourceIndex(classes).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.symbols.isEmpty())
            assertTrue(result.diagnostics.isNotEmpty())
        }
    }

    @Test
    fun `ambiguous same-line overloads and computed properties retain owner obligations`() {
        val source = """
            class Example {
                fun value(x: Int) = x; fun value(x: String) = x
                val computed: Int get() = 1
                fun enclosing(): Int {
                    val local = 2
                    fun nested() = local
                    return nested()
                }
            }
        """.trimIndent()
        KotlinSourceIndex(compile(source)).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertTrue(result.symbols.none { it.identity.contains(".value(") })
            assertTrue(result.symbols.none { it.identity.endsWith(".computed") || it.identity.endsWith(".local") })
            val callable = result.symbols.single { it.identity == "Example.enclosing()I" }
            assertEquals(SourceRange(4, 8), callable.location.line)
            assertEquals(SourceRange(5, 6), callable.location.column)
            assertTrue(result.symbols.any { it.identity == "Example" && it.kind == SymbolKind.CLASS })
        }
    }

    @Test
    fun `abstract external and renamed JVM functions retain class fallback without invented signatures`() {
        val source = """
            abstract class Example {
                abstract fun missingBody(): Int
                external fun nativeValue(): Int
                @JvmName("renamedJvm") fun sourceName() = 1
                fun ordinary() = 2
            }
        """.trimIndent()
        KotlinSourceIndex(compile(source)).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertEquals(setOf("Example.ordinary()I"),
                result.symbols.filter { it.kind == SymbolKind.METHOD }.map { it.identity }.toSet())
            assertTrue(result.symbols.any { it.kind == SymbolKind.CLASS && it.owner == "Example" })
        }
    }

    @Test
    fun `anonymous top-level function keeps emitted callback owner without a guessed method name`() {
        val source = "val callback = fun(value: Int): Int { return value + 1 }"
        KotlinSourceIndex(compile(source)).use { index ->
            val result = index.index(temporary, "Example.kt", Revision.HEAD)
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            assertTrue(result.symbols.none { it.kind == SymbolKind.METHOD })
            assertTrue(result.symbols.any { it.kind == SymbolKind.CLASS && it.owner == "ExampleKt${'$'}callback${'$'}1" })
            assertTrue(result.symbols.any { it.kind == SymbolKind.FIELD && it.identity == "ExampleKt.callback" })
        }
    }

    private fun compile(source: String): Path {
        val sourceFile = temporary.resolve("Example.kt")
        Files.writeString(sourceFile, source)
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-classpath", library,
                "-jvm-target", "17", "-Xlambdas=class", "-d", classes.toString(), sourceFile.toString())
        }
        assertEquals(ExitCode.OK, result, output.toString())
        return classes
    }
}
