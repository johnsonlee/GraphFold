package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.eclipse.jgit.diff.Edit
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A selected control cannot supply the missing body evidence of a separate retained native call. */
class PreparedNativeEvidenceTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `raw input proof retains independent native body gaps with or without a surviving literal change`(enabled: Boolean) {
        val base = revision("base", 1)
        val head = revision("head", 2)
        val changed = changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 4, 3, 4)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(changed), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                    value: $enabled
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(1, result.metadata.obligations)
        assertEquals(0, result.metadata.provedObligations)
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 },
            result.toString())
        assertTrue(result.findings.isEmpty(), "Raw inputs do not authorize fabricated source locations")
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCOMPLETE_GRAPH && "opaque" in it.message }, result.toString())
        assertEquals(setOf(ReasonCode.ANALYSIS_INCOMPLETE), result.reasons.map { it.code }.toSet())
        val difference = "build input effects cannot be independently attributed: Example.value()I"
        assertEquals(enabled, result.reasons.any { difference in it.message }, result.toString())
    }

    private fun revision(name: String, value: Int): PreparedRevision {
        val root = Files.createDirectories(temporary.resolve(name))
        val source = Files.writeString(root.resolve("Example.java"), """
            public class Example {
                public static boolean enabled() { return true; }
                public static native void opaque();
                public static int value() { opaque(); return enabled() ? $value : 0; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val inventory = ContentInventory(mapOf("class/Example.class" to digest(classes.resolve("Example.class"))), emptyMap())
        val scope = StaticScope(classes, emptyList(), emptyList(), production = inventory)
        return PreparedRevision(name, root, mapOf("application" to scope), inventory)
    }
}
