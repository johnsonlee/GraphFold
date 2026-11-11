package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SnapshotComparison
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphite.input.ConstantFold
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinInitializerBindingTest {
    @TempDir lateinit var directory: Path
    private val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `actual static initializer obligations distinguish folded changes and surviving changes`(controlled: Boolean) {
        val condition = if (controlled) "if (gate()) " else ""
        val source = """
            class Example {
                companion object {
                    init { ${condition}System.out.println("before") }
                }
            }
            fun gate(): Boolean = true
        """.trimIndent()
        val before = compile("base", source)
        val after = compile("head", source.replace("before", "after"))
        val indexed = index(after)
        val initializer = indexed.symbols.single { it.identity == "Example.<clinit>()V" }
        val obligation = ProofPlanner.plan(indexed, listOf(initializer.location)).single()
        assertEquals(SymbolKind.METHOD, obligation.kind)
        assertEquals(SourceRange(3, 3), obligation.locations.single().line)
        val rules = if (controlled) listOf(ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("callee_class" to "SampleKt", "callee_name" to "gate")), "value" to false,
        ))) else emptyList()
        val analyzer = GraphiteAnalyzer()
        val comparison = SnapshotComparison(analyzer.analyze(before.resolve("classes"), rules, listOf(library)),
            analyzer.analyze(after.resolve("classes"), rules, listOf(library)))
        assertTrue(comparison.analysisGaps(obligation).isEmpty())
        assertEquals(controlled, comparison.differences(obligation).isEmpty())
    }

    @Test fun `top level scope keeps outer companion and nested lowerings together without unrelated owners`() {
        val root = compile("families", """
            class Example {
                companion object { @JvmField val value = 1; @JvmStatic fun bridge() = value }
                class Nested
            }
            object Additional {
                init { System.out.println("effect") }
                fun touch() {}
            }
        """.trimIndent())
        val indexed = index(root)
        val classes = indexed.symbols.filter { it.kind == SymbolKind.CLASS }.associateBy { it.owner }
        listOf("Example", "Example\$Companion", "Example\$Nested").forEach {
            assertEquals(SourceRange(1, 4), classes.getValue(it).location.line)
        }
        assertEquals(SourceRange(5, 8), classes.getValue("Additional").location.line)
        val initializer = indexed.symbols.single { it.identity == "Additional.<clinit>()V" }
        assertEquals(SourceRange(6, 6), initializer.location.line)
        val planned = ProofPlanner.plan(indexed, listOf(classes.getValue("Additional").location))
        assertTrue(planned.any { it.owner == "Additional" })
        assertTrue(planned.none { it.owner == "Example" || it.owner.startsWith("Example$") })
    }

    @Test fun `unmapped facade or generated owners preserve whole file obligations for every owner`() {
        val root = compile("fallback", """
            class Example
            class Other
            fun topLevel(): Runnable = Runnable { println("callback") }
        """.trimIndent())
        index(root).symbols.filter { it.kind == SymbolKind.CLASS }.forEach {
            assertEquals(SourceRange(1, 3), it.location.line)
        }
    }

    @Test fun `same line initializers remain ambiguous and instance constructors never borrow static evidence`() {
        val sameLine = compile("same-line", "object One { init { println(1) } }; object Two { init { println(2) } }")
        assertTrue(index(sameLine).symbols.none { it.kind == SymbolKind.METHOD })
        val instance = compile("instance", """
            class Example {
                constructor()
                constructor(value: Int)
                init { println("instance") }
            }
            val anonymous = object { init { println("anonymous") } }
            object Static {
                init { println("static") }
            }
        """.trimIndent())
        val methods = index(instance).symbols.filter { it.kind == SymbolKind.METHOD }
        assertEquals(listOf("Static.<clinit>()V"), methods.map { it.identity })
    }

    @Test fun `missing line tables cannot bind an initializer to a nearby emitted method`() {
        val root = compile("no-lines", "object Example { init { println(1) } }")
        rewrite(root.resolve("classes/Example.class")) { node ->
            node.methods.forEach { method -> method.instructions.toArray().filterIsInstance<LineNumberNode>()
                .forEach(method.instructions::remove) }
        }
        assertTrue(index(root).symbols.none { it.kind == SymbolKind.METHOD })
        assertTrue(index(root).symbols.any { it.kind == SymbolKind.CLASS })
    }

    @ParameterizedTest @ValueSource(ints = [1, 5])
    fun `line evidence before or after the initializer cannot promote it`(observedLine: Int) {
        val root = compile("outside-lines-$observedLine", """
            object Example {
                init {
                    println(1)
                }
            }
        """.trimIndent())
        rewrite(root.resolve("classes/Example.class")) { node ->
            node.methods.filter { it.name == "<clinit>" }
                .flatMap { it.instructions.toArray().filterIsInstance<LineNumberNode>() }
                .forEach { it.line = observedLine }
        }
        val indexed = index(root)
        assertTrue(indexed.symbols.none { it.identity.endsWith(".<clinit>()V") })
        assertTrue(indexed.symbols.any { it.kind == SymbolKind.CLASS })
    }

    @Test fun `duplicate metadata names and duplicate source declarations retain file wide fallback`() {
        val root = compile("duplicate", "class Example\nclass Other")
        val source = root.resolve("classes/Example.class")
        val node = ClassNode().apply { ClassReader(Files.readAllBytes(source)).accept(this, 0) }
        node.name = "Alias"
        write(root.resolve("classes/Alias.class"), node)
        val indexed = index(root)
        listOf("Example", "Alias").forEach { owner ->
            assertEquals(SourceRange(1, 2), indexed.symbols.single { it.owner == owner }.location.line)
        }
        Files.delete(root.resolve("classes/Alias.class"))
        Files.writeString(root.resolve("Sample.kt"), "class Example\nclass Example\nclass Other")
        assertEquals(SourceRange(1, 3), index(root).symbols.single { it.owner == "Example" }.location.line)
    }

    @Test fun `unknown or malformed metadata never narrows a source scope`() {
        val root = compile("metadata", "class Example\nclass Other")
        val original = Files.readAllBytes(root.resolve("classes/Example.class"))
        for ((key, value) in listOf("mv" to listOf(999), "d1" to 7)) {
            Files.write(root.resolve("classes/Example.class"), original)
            rewrite(root.resolve("classes/Example.class")) { node ->
                val metadata = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
                metadata.values[metadata.values.indexOf(key) + 1] = value
            }
            assertEquals(SourceRange(1, 2), index(root).symbols.single { it.owner == "Example" }.location.line)
        }
    }

    @Test fun `another owner line table cannot replace missing expected owner initialization evidence`() {
        val root = compile("wrong-owner", """
            object Expected {
                init { println("expected") }
            }
            object Unrelated {
                init { println("unrelated") }
            }
        """.trimIndent())
        val expected = root.resolve("classes/Expected.class")
        val original = ClassNode().apply { ClassReader(Files.readAllBytes(expected)).accept(this, 0) }
        rewrite(expected) { node -> node.methods.filter { it.name == "<clinit>" }.forEach { method ->
            method.instructions.toArray().filterIsInstance<LineNumberNode>().forEach(method.instructions::remove)
        } }
        rewrite(root.resolve("classes/Unrelated.class")) { node ->
            node.methods.filter { it.name == "<clinit>" }.flatMap { it.instructions.toArray().filterIsInstance<LineNumberNode>() }
                .forEach { it.line = 2 }
        }
        assertTrue(index(root).symbols.none { it.identity.endsWith(".<clinit>()V") })
        original.name = "Alias"
        write(root.resolve("classes/Alias.class"), original)
        assertTrue(index(root).symbols.none { it.identity.endsWith(".<clinit>()V") },
            "Duplicate metadata owners cannot lend a unique remaining line table")
    }

    @Test fun `same line properties delegates accessors and functions prevent initializer promotion`() {
        val competitors = listOf("val value = System.nanoTime()", "val value by lazy { 1 }",
            "val value get() = System.nanoTime()", "fun value() = System.nanoTime()")
        competitors.forEachIndexed { caseIndex, declaration ->
            val root = compile("competitor-$caseIndex", "object Example { $declaration; init { println(1) } }")
            assertTrue(index(root).symbols.none { it.identity.endsWith(".<clinit>()V") }, declaration)
        }
    }

    @Test fun `semantically invalid local or top level companion declarations cannot invent owner bindings`() {
        val root = compile("invalid-ownership", "object Example { init { println(1) } }")
        for (source in listOf("companion object Example { init { println(1) } }",
            "class Container { init { object Local { init { println(1) } } } }")) {
            Files.writeString(root.resolve("Sample.kt"), source)
            assertTrue(index(root).symbols.none { it.identity.endsWith(".<clinit>()V") })
        }
    }

    @Test fun `initializer local declarations and default accessors are not competing executable ranges`() {
        val root = compile("local-declarations", """
            object Example {
                lateinit var text: String
                    private set
                init {
                    val value = 1
                    fun read() = value
                    println(read())
                }
            }
        """.trimIndent())
        val initializer = index(root).symbols.single { it.identity == "Example.<clinit>()V" }
        assertEquals(SourceRange(4, 8), initializer.location.line)
    }

    private fun index(root: Path) = KotlinSourceIndex(root.resolve("classes")).use { it.index(root, "Sample.kt", Revision.HEAD) }

    private fun compile(name: String, source: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val input = Files.writeString(root.resolve("Sample.kt"), source)
        val classes = Files.createDirectory(root.resolve("classes"))
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { stream -> K2JVMCompiler().exec(stream,
            "-no-stdlib", "-no-reflect", "-classpath", library.toString(), "-jvm-target", "17",
            "-d", classes.toString(), input.toString()) }
        assertEquals(ExitCode.OK, result, output.toString())
        return root
    }

    private fun rewrite(path: Path, action: (ClassNode) -> Unit) {
        val node = ClassNode().apply { ClassReader(Files.readAllBytes(path)).accept(this, 0) }
        action(node)
        write(path, node)
    }

    private fun write(path: Path, node: ClassNode) {
        val writer = ClassWriter(0)
        node.accept(writer)
        Files.write(path, writer.toByteArray())
    }
}
