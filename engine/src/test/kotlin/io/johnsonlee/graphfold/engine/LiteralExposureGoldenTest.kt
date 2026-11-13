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
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Literal receiver evidence is narrower than readonly-looking or nullable arbitrary equals receivers. */
class LiteralExposureGoldenTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @EnumSource(Case::class)
    fun `literal string exposure preserves null safety and custom receiver effects`(case: Case) {
        val base = compile("base", "return enabled() ? 20 : 10;")
        val head = compile("head", case.body)
        assertEquals("value:10;count=0", execute(base, false, null))
        assertEquals(case.nullOff, execute(head, false, null))
        assertEquals("value:20;count=0", execute(base, true, null))
        assertEquals(case.nullOn, execute(head, true, null))
        assertEquals(case.selectedOn, execute(head, true, "Android"))
        val obligation = ProofObligation("LiteralExposure.quote(Ljava/lang/String;Ljava/lang/String;)I",
            SymbolKind.METHOD, "LiteralExposure", emptyList())
        for (enabled in listOf(false, true)) {
            val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
                "callee_class" to "LiteralExposure", "callee_name" to "enabled")), "value" to enabled))
            val analyzer = GraphiteAnalyzer()
            val before = analyzer.analyze(base, listOf(fold))
            val after = analyzer.analyze(head, listOf(fold))
            assertEquals(listOf(1), before.matchedRules)
            assertEquals(listOf(1), after.matchedRules)
            val comparison = SnapshotComparison(before, after)
            assertTrue(before.diagnostics.isEmpty() && after.diagnostics.isEmpty())
            assertTrue(comparison.analysisGaps(obligation).isEmpty(), comparison.analysisGaps(obligation).toString())
            assertEquals(!enabled && case.controlledOff, comparison.differences(obligation).isEmpty(),
                "$case AB=$enabled: ${comparison.differences(obligation)}")
        }
    }

    private fun compile(name: String, body: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("LiteralExposure.java")
        Files.writeString(source, """
            public class LiteralExposure {
                public static boolean flag;
                public static int count;
                static boolean enabled() { return flag; }
                public static int quote(String value, String candidate) { $body }
                public static final class Custom {
                    public boolean equals(Object value) { count++; return "Android".equals(value); }
                    public int hashCode() { return 1; }
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun execute(classes: Path, enabled: Boolean, value: String?): String =
        URLClassLoader(arrayOf(classes.toUri().toURL())).use { loader ->
            val type = loader.loadClass("LiteralExposure")
            type.getField("flag").setBoolean(null, enabled)
            val result = try {
                "value:${type.getMethod("quote", String::class.java, String::class.java).invoke(null, value, null)}"
            } catch (failure: InvocationTargetException) {
                "throws:${failure.targetException.javaClass.name}"
            }
            "$result;count=${type.getField("count").getInt(null)}"
        }

    enum class Case(val body: String, val controlledOff: Boolean, val nullOff: String, val nullOn: String, val selectedOn: String) {
        LITERAL("if (\"Android\".equals(value) && enabled()) return 20; return 10;", true,
            "value:10;count=0", "value:10;count=0", "value:20;count=0"),
        ALIAS("String literal = \"Android\"; String alias = literal; " +
            "if (alias.equals(value) && enabled()) return 20; return 10;", true,
            "value:10;count=0", "value:10;count=0", "value:20;count=0"),
        NULLABLE("if (candidate.equals(value) && enabled()) return 20; return 10;", false,
            "throws:java.lang.NullPointerException;count=0", "throws:java.lang.NullPointerException;count=0",
            "throws:java.lang.NullPointerException;count=0"),
        REASSIGNED("if (value != null) candidate = \"Android\"; " +
            "if (candidate.equals(value) && enabled()) return 20; return 10;", false,
            "throws:java.lang.NullPointerException;count=0", "throws:java.lang.NullPointerException;count=0", "value:20;count=0"),
        CUSTOM("if (new Custom().equals(value) && enabled()) return 20; return 10;", false,
            "value:10;count=1", "value:10;count=1", "value:20;count=1"),
    }
}
