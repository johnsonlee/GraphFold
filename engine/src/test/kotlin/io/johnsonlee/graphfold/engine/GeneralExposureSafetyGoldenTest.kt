package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Actual compiler and graph evidence; no fixture execution is used to establish control coverage. */
class GeneralExposureSafetyGoldenTest {
    @TempDir lateinit var directory: Path

    @Test fun `literal receiver equality is safe for every nullable argument`() = verify(
        "return \"ready\".equals(text);", controlledOff = true,
    )

    @Test fun `short circuit null guard protects a String operation inside the helper`() = verify(
        "return text != null && text.length() > 0;", controlledOff = true,
    )

    @Test fun `guarding a stable local nullable helper result establishes whole helper safety`() = verify(
        "String value = nullable(text); return value != null && value.length() > 0;", controlledOff = true,
    )

    @Test fun `unguarded nullable receiver keeps the exposure obligation`() = verify(
        "return text.length() > 0;", controlledOff = false,
    )

    @Test fun `guard on input does not establish nonnullness of a nullable intermediate`() = verify(
        "return text != null && nullable(text).length() > 0;", controlledOff = false,
    )

    @Test fun `effectful predicate cannot disappear with the selected branch`() = verify(
        "count++; return text != null;", controlledOff = false,
    )

    @Test fun `unsupported monitor operations are not a complete helper proof`() = verify(
        "synchronized (text) { return true; }", controlledOff = false,
    )

    @Test fun `recursive predicates have no termination proof`() = verify(
        "return eligible(text);", controlledOff = false,
    )

    @Test fun `safe helper does not excuse an effectful argument producer`() = verify(
        "return \"ready\".equals(text);", controlledOff = false,
        body = "if (eligible(produced(text)) && enabled()) return 20; return 10;",
    )

    @Test fun `predicate result reused in unrelated fallback remains an obligation`() = verify(
        "return \"ready\".equals(text);", controlledOff = false,
        body = "boolean value = eligible(text); if (value && enabled()) return 20; return value ? 30 : 10;",
    )

    @Test fun `selected folding inside a previously safe helper cannot erase its newly exposed nullable receiver`() = verify(
        "if (enabled()) return true; return text.length() > 0;", controlledOff = false,
        enabledBody = "return true;", expectedMatches = 2,
    )

    private fun verify(
        helper: String, controlledOff: Boolean,
        body: String = "if (eligible(text) && enabled()) return 20; return 10;",
        enabledBody: String = "return flag;", expectedMatches: Int = 1,
    ) {
        val base = compile("base", helper, "return 10;", enabledBody)
        val head = compile("head", helper, body, enabledBody)
        val obligation = ProofObligation("Exposure.quote(Ljava/lang/String;)I", SymbolKind.METHOD, "Exposure", emptyList())
        for (enabled in listOf(false, true)) {
            val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
                "callee_class" to "Exposure", "callee_name" to "enabled")), "value" to enabled))
            val analyzer = GraphiteAnalyzer()
            val before = analyzer.analyze(base, emptyList())
            val after = analyzer.analyze(head, listOf(fold))
            assertEquals(listOf(expectedMatches), after.matchedRules)
            assertTrue(before.diagnostics.isEmpty(), before.diagnostics.toString())
            assertTrue(after.diagnostics.isEmpty(), after.diagnostics.toString())
            val comparison = SnapshotComparison(before, after)
            assertTrue(comparison.analysisGaps(obligation).isEmpty(), comparison.analysisGaps(obligation).toString())
            assertEquals(!enabled && controlledOff, comparison.differences(obligation).isEmpty(),
                "selected enabled=$enabled; helper=$helper; differences=${comparison.differences(obligation)}\n" +
                    after.methods.getValue(obligation.identity).canonical)
        }
    }

    private fun compile(name: String, helper: String, body: String, enabledBody: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Exposure.java")
        Files.writeString(source, """
            public class Exposure {
                public static boolean flag;
                public static int count;
                static boolean enabled() { $enabledBody }
                private static String nullable(String text) { return text == null || text.length() == 0 ? null : text; }
                private static String produced(String text) { count++; return text; }
                private static boolean eligible(String text) { $helper }
                public static int quote(String text) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }
}
