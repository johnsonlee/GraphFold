package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Java argument evaluation remains an obligation independently of a helper's total body. */
class GeneralExposureArgumentGoldenTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest(name = "{0}")
    @MethodSource("arguments")
    fun `only supported total argument producers disappear with the selected branch`(case: ArgumentCase) {
        val base = compile("base", case, "return 10;")
        val head = compile("head", case, "if (eligible(${case.argument}) && enabled()) return 20; return 10;")
        val obligation = ProofObligation("Exposure.quote(Ljava/lang/String;)I", SymbolKind.METHOD, "Exposure", emptyList())
        val before = GraphiteAnalyzer().analyze(base, emptyList())
        assertTrue(before.diagnostics.isEmpty(), before.diagnostics.toString())
        for (enabled in listOf(false, true)) {
            val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
                "callee_class" to "Exposure", "callee_name" to "enabled")), "value" to enabled))
            val after = GraphiteAnalyzer().analyze(head, listOf(rule))
            assertEquals(listOf(1), after.matchedRules)
            assertTrue(after.diagnostics.isEmpty(), after.diagnostics.toString())
            val comparison = SnapshotComparison(before, after)
            assertTrue(comparison.analysisGaps(obligation).isEmpty(), comparison.analysisGaps(obligation).toString())
            assertEquals(!enabled && case.disposable, comparison.differences(obligation).isEmpty(),
                "$case, enabled=$enabled: ${comparison.differences(obligation)}")
        }
    }

    private fun compile(name: String, case: ArgumentCase, body: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Exposure.java")
        Files.writeString(source, """
            public class Exposure {
                public static boolean flag;
                static boolean enabled() { return flag; }
                private static boolean eligible(${case.parameter}) { ${case.helper} }
                public static int quote(String text) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    data class ArgumentCase(
        val name: String, val parameter: String, val argument: String, val helper: String, val disposable: Boolean,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic fun arguments(): List<ArgumentCase> = listOf(
            ArgumentCase("nullable reference local", "String value", "text", "return value != null;", true),
            ArgumentCase("literal String", "String value", "\"ready\"", "return value != null;", true),
            ArgumentCase("literal null", "String value", "null", "return value == null;", true),
            ArgumentCase("integer literal", "int value", "7", "return value > 0;", true),
            ArgumentCase("long literal remains conservatively unsupported", "long value", "7L", "return true;", false),
            ArgumentCase("class literal has separate resolution evidence", "Class<?> value", "Exposure.class", "return true;", false),
        )
    }
}
