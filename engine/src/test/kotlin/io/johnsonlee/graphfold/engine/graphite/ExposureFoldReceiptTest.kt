package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.MethodFoldObservation
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.sootup.MethodObservationEvidence
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExposureFoldReceiptTest {
    @TempDir lateinit var directory: Path

    @Test fun `zero applied count cannot authorize erasure even with a selected site and redundant folded branch`() {
        val source = directory.resolve("Exposure.java")
        Files.writeString(source, """
            public class Exposure {
                public static boolean enabled() { return true; }
                public static int quote(int value) { if (value > 0 && enabled()) return 20; return 10; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val exposure = ExposureProvenance()
        val plain = JavaProjectLoader(LoaderConfig(), false).loadObserved(classes, exposure::capture)
        exposure.select(0, plain.graph.nodes(CallSiteNode::class.java).filter { it.callee.identity == "Exposure.enabled()Z" }.toList())
        val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
            "callee_class" to "Exposure", "callee_name" to "enabled")), "value" to false))
        val successful = SnapshotSink(exposure = exposure)
        val zeroApplied = SnapshotSink(exposure = exposure)
        JavaProjectLoader(LoaderConfig(folding = FoldPlan(listOf(rule))), false).loadObserved(classes) { observation ->
            successful.capture(observation)
            val original = observation.evidence.folding
            // Inject a zero-count receipt at the public observation boundary; it grants no normalization authority.
            val zero = original?.let { receipt -> object : MethodFoldObservation by receipt {
                override val foldedCalls = receipt.foldedCalls.mapValues { 0 }
            } }
            zeroApplied.capture(object : FoldedMethodObservation by observation {
                override val evidence = object : MethodObservationEvidence by observation.evidence {
                    override val folding = zero
                }
            })
        }
        val identity = "Exposure.quote(I)I"
        assertEquals(mapOf(0 to 1), successful.observations.getValue(identity).folding?.foldedCalls)
        assertEquals(mapOf(0 to 0), zeroApplied.observations.getValue(identity).folding?.foldedCalls)
        assertFalse(successful.methods.getValue(identity).canonical.contains("JIfStmt"))
        assertTrue(zeroApplied.methods.getValue(identity).canonical.contains("JIfStmt"),
            "Selection without a positive applied count must preserve the residual production condition")
    }
}
