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

/** Labels describe the control contract; independent execution checks effects, not the analyzer's current verdict. */
class ExposureBeforeAbGoldenTest {
    @TempDir lateinit var directory: Path

    @Test fun `total primitive exposure before disabled AB is disposable`() = verify(
        "if (x > 0 && enabled()) return 20; return 10;", controlledOff = true,
    )

    @Test fun `compound OR and AND exposure remains controlled by disabled AB`() = verify(
        "if (((x > 0 && x < 10) || x < -10) && enabled()) return 20; return 10;", controlledOff = true,
    )

    @Test fun `total pure private helper exposure before disabled AB is disposable`() = verify(
        "if (exposed(x) && enabled()) return 20; return 10;", controlledOff = true,
    )

    @Test fun `division exposure before disabled AB preserves possible exception`() = verify(
        "if (10 / x > 0 && enabled()) return 20; return 10;", controlledOff = false, offInput = 0,
        expectedOff = "throws:java.lang.ArithmeticException;count=0",
    )

    @Test fun `counter exposure before disabled AB preserves its side effect`() = verify(
        "if (counted(x) && enabled()) return 20; return 10;", controlledOff = false,
        expectedOff = "value:10;count=1", expectedOn = "value:20;count=1",
    )

    @Test fun `exposure early return changes fallback even with AB disabled`() = verify(
        "if (x <= 0) return 30; if (enabled()) return 20; return 10;", controlledOff = false, offInput = -1,
        expectedOff = "value:30;count=0",
    )

    @Test fun `unrelated redundant condition beside AB remains uncontrolled`() = verify(
        "if (enabled()) return 20; if (x > 0) return 10; return 10;", controlledOff = false,
    )

    @Test fun `exposure value reused by an unrelated fallback condition cannot be discarded`() = verify(
        "boolean exposed = exposed(x); if (exposed && enabled()) return 20; return exposed ? 30 : 10;",
        controlledOff = false, expectedOff = "value:30;count=0",
    )

    private fun verify(
        body: String, controlledOff: Boolean, offInput: Int = 1,
        expectedOff: String = "value:10;count=0", expectedOn: String = "value:20;count=0",
    ) {
        val base = compile("base", "return 10;")
        val head = compile("head", body)
        assertEquals("value:10;count=0", execute(base, false, offInput))
        assertEquals(expectedOff, execute(head, false, offInput))
        assertEquals("value:10;count=0", execute(base, true, 1))
        assertEquals(expectedOn, execute(head, true, 1))
        val obligation = ProofObligation("Exposure.quote(I)I", SymbolKind.METHOD, "Exposure", emptyList())
        for (enabled in listOf(false, true)) {
            val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
                "callee_class" to "Exposure", "callee_name" to "enabled")), "value" to enabled))
            val analyzer = GraphiteAnalyzer()
            // BASE has no selected call; no fold audit is required for this lower-level comparison.
            val before = analyzer.analyze(base, emptyList())
            val after = analyzer.analyze(head, listOf(fold))
            assertEquals(listOf(1), after.matchedRules)
            val comparison = SnapshotComparison(before, after)
            assertTrue(before.diagnostics.isEmpty() && after.diagnostics.isEmpty())
            assertTrue(comparison.analysisGaps(obligation).isEmpty(), comparison.analysisGaps(obligation).toString())
            val expectedControlled = !enabled && controlledOff
            assertEquals(expectedControlled, comparison.differences(obligation).isEmpty(),
                "AB=$enabled expected ${if (expectedControlled) "controlled" else "uncontrolled"}: " +
                    comparison.differences(obligation) + "\nBASE:\n" + before.methods.getValue(obligation.identity).canonical +
                    "\nHEAD:\n" + after.methods.getValue(obligation.identity).canonical)
        }
    }

    private fun compile(name: String, body: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Exposure.java")
        Files.writeString(source, """
            public class Exposure {
                public static boolean flag;
                public static int count;
                static boolean enabled() { return flag; }
                private static boolean exposed(int x) { return x > 0; }
                private static boolean counted(int x) { count++; return x > 0; }
                public static int quote(int x) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun execute(classes: Path, enabled: Boolean, input: Int): String =
        URLClassLoader(arrayOf(classes.toUri().toURL())).use { loader ->
            val type = loader.loadClass("Exposure")
            type.getField("flag").setBoolean(null, enabled)
            val result = try {
                "value:${type.getMethod("quote", Int::class.javaPrimitiveType).invoke(null, input)}"
            } catch (failure: InvocationTargetException) {
                "throws:${failure.targetException.javaClass.name}"
            }
            "$result;count=${type.getField("count").getInt(null)}"
        }
}
