package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphite.input.ConstantFold
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposureReferenceGoldenTest {
    @TempDir lateinit var directory: Path

    @Test fun `reference identity exposure neither dereferences null nor invokes equals`() {
        listOf("context != null", "context == null").forEachIndexed { index, condition ->
            verify("identity$index", condition, controlledOff = true)
        }
    }

    @Test fun `field evaluation before reference exposure retains its null dereference`() {
        verify("field", "context.value != null", controlledOff = false)
    }

    private fun verify(name: String, condition: String, controlledOff: Boolean) {
        val base = compile("$name-base", "return 10;")
        val head = compile("$name-head", "if ($condition && enabled()) return 20; return 10;")
        assertEquals("10", execute(base, false, present = false))
        assertEquals(if (controlledOff) "10" else "java.lang.NullPointerException", execute(head, false, present = false))
        assertEquals("10", execute(head, false, present = true))
        val selectedPresent = condition != "context == null"
        assertEquals("20", execute(head, true, present = selectedPresent))
        val obligation = ProofObligation("ReferenceExposure.quote(LReferenceExposure;)I",
            SymbolKind.METHOD, "ReferenceExposure", emptyList())
        for (enabled in listOf(false, true)) {
            val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
                "callee_class" to "ReferenceExposure", "callee_name" to "enabled")), "value" to enabled))
            val analyzer = GraphiteAnalyzer()
            val before = analyzer.analyze(base, emptyList())
            val after = analyzer.analyze(head, listOf(rule))
            assertEquals(listOf(1), after.matchedRules)
            val comparison = SnapshotComparison(before, after)
            assertTrue(before.diagnostics.isEmpty() && after.diagnostics.isEmpty())
            assertTrue(comparison.analysisGaps(obligation).isEmpty(), comparison.analysisGaps(obligation).toString())
            assertEquals(!enabled && controlledOff, comparison.differences(obligation).isEmpty(), "AB=$enabled $condition")
        }
    }

    private fun compile(name: String, body: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("ReferenceExposure.java")
        Files.writeString(source, """
            public class ReferenceExposure {
                public static boolean flag;
                public Object value = new Object();
                static boolean enabled() { return flag; }
                public static int quote(ReferenceExposure context) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun execute(classes: Path, enabled: Boolean, present: Boolean): String =
        URLClassLoader(arrayOf(classes.toUri().toURL())).use { loader ->
            val type = loader.loadClass("ReferenceExposure")
            type.getField("flag").setBoolean(null, enabled)
            val context = if (present) type.getConstructor().newInstance() else null
            try {
                type.getMethod("quote", type).invoke(null, context).toString()
            } catch (failure: InvocationTargetException) {
                failure.targetException.javaClass.name
            }
        }
}
