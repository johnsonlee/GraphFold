package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaticSafetyResidualTest {
    @TempDir lateinit var temporary: Path
    private var sequence = 0

    @Test fun `constant callee does not erase unused production return changes`() {
        val base = analyze("if (always()) return x; return 10;")
        val head = analyze("if (always()) return x; return 20;")
        assertNotNull(base.semanticKey)
        assertNotNull(head.semanticKey)
        assertNotEquals(base.semanticKey, head.semanticKey)
        assertTrue(head.nonThrowing.evidence.any { it.contains("Unselected instructions") })
    }

    @Test fun `unused effects and throws remain in the whole method certificate`() {
        val base = analyze("if (always()) return x; return 10;")
        val effect = analyze("if (always()) return x; count++; return 10;")
        val hazard = analyze("if (always()) return x; throw new IllegalStateException();")
        assertNotEquals(base.semanticKey, effect.semanticKey)
        assertNotEquals(base.semanticKey, hazard.semanticKey)
        assertTrue(effect.effectFree.evidence.any { it.contains("retained exactly") })
        assertTrue(hazard.nonThrowing.evidence.any { it.contains("retained exactly") })
    }

    @Test fun `unchanged unused instructions do not prevent equivalent analyzed computation proof`() {
        val base = analyze("if (always()) return x; return 10;")
        val head = analyze("if (always()) return x + 0; return 10;")
        assertNotNull(base.semanticKey)
        assertEquals(base.semanticKey, head.semanticKey)
    }

    @Test fun `callee residual certificate reaches its callers`() {
        val base = analyze("return helper(x);", "if (always()) return x; return 10;")
        val head = analyze("return helper(x);", "if (always()) return x; return 20;")
        assertNotEquals(base.semanticKey, head.semanticKey)
        assertTrue(head.dependencies.any { it.contains("helper") })
        assertTrue(head.nonThrowing.evidence.any { it.contains("ResidualExample.helper") })
    }

    private fun analyze(body: String, helper: String = "return x;"): MethodSafetySummary {
        val directory = Files.createDirectory(temporary.resolve("case-${sequence++}"))
        val file = directory.resolve("ResidualExample.java")
        Files.writeString(file, """
            public final class ResidualExample {
                static int count;
                private static boolean always() { return true; }
                private static int helper(int x) { $helper }
                public static int value(int x) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectory(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()))
        return StaticSafetyAnalysis(classes, emptyList()).use { it.summaries().getValue("ResidualExample.value(I)I") }
    }
}
