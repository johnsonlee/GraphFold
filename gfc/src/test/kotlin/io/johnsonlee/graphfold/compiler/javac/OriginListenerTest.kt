package io.johnsonlee.graphfold.compiler.javac

import com.sun.source.util.JavacTask
import com.sun.source.util.TaskEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginListenerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `generation event outside checkout cannot publish provenance even without earlier analysis event`() {
        val source = Files.writeString(temporary.resolve("Example.java"), "class Example {}")
        val checkout = Files.createDirectory(temporary.resolve("checkout"))
        val output = temporary.resolve("origins")
        val compiler = ToolProvider.getSystemJavaCompiler()
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { manager ->
            val task = compiler.getTask(null, manager, null, listOf("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(listOf(source))) as JavacTask
            val unit = task.parse().single()
            val type = task.analyze().single() as javax.lang.model.element.TypeElement
            val listener = OriginListener(task, output, checkout, "generation-only")
            val failure = assertFailsWith<IllegalStateException> {
                listener.finished(TaskEvent(TaskEvent.Kind.GENERATE, unit, type))
            }
            assertTrue(failure.cause?.message.orEmpty().contains("Generated source is outside checkout"))
            listener.finished(TaskEvent(TaskEvent.Kind.COMPILATION))
            assertFalse(Files.exists(output), "A failed generation event must not publish a completed sidecar")
        }
    }
}
