package io.johnsonlee.graphfold.compiler.javac

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TaskEvent
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import java.nio.file.Files
import java.nio.file.Path
import javax.lang.model.element.Element
import javax.tools.ToolProvider
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Real compiler trees with damaged attribution evidence must never publish a complete sidecar. */
class OriginAttributionBoundaryTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["negative", "empty", "reversed", "outside", "allocation"])
    fun `invalid declaration or allocation offsets fail before recording provenance`(damage: String) = withFixture { fixture ->
        val positions = fixture.trees.sourcePositions
        fixture.inject(object : ForwardingTrees(fixture.trees) {
            override fun getSourcePositions(): SourcePositions = object : SourcePositions {
                override fun getStartPosition(unit: CompilationUnitTree, tree: Tree): Long {
                    val original = positions.getStartPosition(unit, tree)
                    return when {
                        tree === fixture.anonymous && damage == "negative" -> -1
                        tree === fixture.anonymous && damage == "empty" -> positions.getEndPosition(unit, tree)
                        tree === fixture.anonymous && damage == "reversed" -> positions.getEndPosition(unit, tree) + 1
                        tree === fixture.allocation && damage == "allocation" -> -1
                        else -> original
                    }
                }
                override fun getEndPosition(unit: CompilationUnitTree, tree: Tree): Long =
                    if (tree === fixture.anonymous && damage == "outside") fixture.sourceLength + 1L
                    else positions.getEndPosition(unit, tree)
            }
        })
        fixture.assertFailure("Missing compiler source range")
    }

    @Test fun `conflicting origins across analysis events cannot overwrite the first attributed range`() = withFixture { fixture ->
        fixture.analyze()
        val positions = fixture.trees.sourcePositions
        fixture.inject(object : ForwardingTrees(fixture.trees) {
            override fun getSourcePositions(): SourcePositions = object : SourcePositions {
                override fun getStartPosition(unit: CompilationUnitTree, tree: Tree): Long =
                    positions.getStartPosition(unit, tree) + if (tree === fixture.anonymous) 1 else 0
                override fun getEndPosition(unit: CompilationUnitTree, tree: Tree): Long = positions.getEndPosition(unit, tree)
            }
        })
        fixture.assertFailure("Conflicting compiler origins")
    }

    @Test fun `missing attributed class symbols cannot silently produce a complete empty manifest`() = withFixture { fixture ->
        fixture.inject(object : ForwardingTrees(fixture.trees) {
            override fun getElement(path: TreePath): Element? =
                if (path.leaf === fixture.anonymous) null else super.getElement(path)
        })
        fixture.assertFailure("Missing attributed class symbol")
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val text = "class Example { Object value = new Object() { int member = 1; }; }"
        val source = Files.writeString(temporary.resolve("Example.java"), text)
        val compiler = ToolProvider.getSystemJavaCompiler()
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { manager ->
            val task = assertIs<JavacTask>(compiler.getTask(null, manager, null, listOf("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(listOf(source))))
            val unit = task.parse().single()
            task.analyze()
            val anonymousPaths = mutableListOf<TreePath>()
            object : TreePathScanner<Unit, Unit>() {
                override fun visitClass(node: ClassTree, unused: Unit?) {
                    if (node.simpleName.isEmpty()) anonymousPaths += currentPath
                    super.visitClass(node, unused)
                }
            }.scan(unit, null)
            val path = anonymousPaths.single()
            val output = temporary.resolve("origins")
            test(Fixture(OriginListener(task, output, temporary, "malformed-attribution"), Trees.instance(task), unit,
                path.leaf, path.parentPath.leaf, text.length, output))
        }
    }

    private class Fixture(
        val listener: OriginListener, val trees: Trees, val unit: CompilationUnitTree,
        val anonymous: Tree, val allocation: Tree, val sourceLength: Int, val output: Path,
    ) {
        fun inject(replacement: Trees) {
            OriginListener::class.java.getDeclaredField("trees").apply { isAccessible = true }.set(listener, replacement)
        }
        fun analyze() { listener.finished(TaskEvent(TaskEvent.Kind.ANALYZE, unit)) }
        fun assertFailure(message: String) {
            val failure = assertFailsWith<IllegalStateException> { analyze() }
            assertTrue(failure.cause?.message.orEmpty().contains(message), failure.toString())
            listener.finished(TaskEvent(TaskEvent.Kind.COMPILATION))
            assertFalse(Files.exists(output), "A rejected analysis event must remain failed at compilation completion")
        }
    }
}
