package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaCompilerInputsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `producer roots and API dependencies override unrelated duplicate source and library names`() {
        val wrongSource = directory("wrong-source/library")
        val rightSource = directory("right-source/library")
        val wrong = compileApi(wrongSource, "Wrong")
        val right = compileApi(rightSource, "Token")
        val own = directory("app/src")
        val generated = directory("app/generated")
        val unrelated = wrongSource.parent
        val output = directory("app/classes")
        Files.writeString(own.resolve("App.java"),
            "class App extends library.Api { Token echo(Token value) { return value; } }")
        val project = StaticScope(output, listOf(unrelated, own, generated), listOf(wrong, right),
            sourceOutputs = mapOf(own to listOf(output), generated to listOf(output), unrelated to listOf(wrong)),
            sourceClasspaths = mapOf(own to listOf(right), generated to listOf(right), unrelated to listOf(wrong)))
        val inputs = javaCompilerInputs(project, own.resolve("App.java"))
        assertEquals(listOf(own, generated), inputs.roots)
        assertEquals(listOf(right), inputs.dependencies)
        val indexed = JavaSourceIndex(inputs.roots, inputs.dependencies).use { it.index(own, "App.java", Revision.HEAD) }
        assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
        assertTrue(indexed.symbols.any { it.identity == "App.echo(Llibrary/Api${'$'}Token;)Llibrary/Api${'$'}Token;" })
        val union = JavaSourceIndex(project.sourceRoots, project.dependencies).use { it.index(own, "App.java", Revision.HEAD) }
        assertTrue(union.diagnostics.isNotEmpty(), "Unrelated duplicate sources must not bind this producer's API")
    }

    @Test fun `longest owned root selects exact compile context and missing owned context fails closed`() {
        val root = directory("src")
        val nested = directory("src/generated")
        val classes = directory("classes")
        val project = StaticScope(classes, listOf(root, nested), emptyList(),
            sourceClasspaths = mapOf(root to listOf(classes), nested to emptyList()))
        assertEquals(JavaCompilerInputs(listOf(nested), emptyList()), javaCompilerInputs(project, nested.resolve("App.java")))
        assertFailsWith<IllegalArgumentException> { javaCompilerInputs(project, temporary.resolve("unowned/App.java")) }
        val alias = Files.createSymbolicLink(temporary.resolve("source-alias"), root)
        assertEquals(JavaCompilerInputs(listOf(root), listOf(classes)), javaCompilerInputs(project, alias.resolve("App.java")))
    }

    @Test fun `legacy source-only inputs retain their original parser context`() {
        val root = directory("src")
        val compiled = directory("classes")
        val dependency = directory("dependency")
        val project = StaticScope(compiled, listOf(root), listOf(dependency))
        assertEquals(JavaCompilerInputs(listOf(root), listOf(dependency)), javaCompilerInputs(project, root.resolve("App.java")))
        assertEquals(JavaCompilerInputs(listOf(root), emptyList()),
            javaCompilerInputs(project.copy(sourceDependencies = emptyList()), root.resolve("App.java")))
    }

    private fun directory(name: String): Path = Files.createDirectories(temporary.resolve(name))

    private fun compileApi(source: Path, type: String): Path {
        val file = source.resolve("Api.java")
        Files.writeString(file, "package library; public class Api { public static class $type {} }")
        val output = directory("$type-classes")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), file.toString()))
        return output
    }
}
