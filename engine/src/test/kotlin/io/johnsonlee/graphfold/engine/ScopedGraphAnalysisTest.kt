package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopedGraphAnalysisTest {
    @TempDir lateinit var directory: Path

    @Test fun `bounded third party consumer in unrelated class no longer poisons source proof`() {
        val snapshot = snapshot()
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue(snapshot.methodDiagnostics.isNotEmpty())
        val comparison = SnapshotComparison(snapshot, snapshot)
        assertTrue(comparison.analysisGaps(obligation("Sample.run()I")).isEmpty())
        assertTrue(comparison.analysisGaps().isNotEmpty())
    }

    @Test fun `known consumer can observe another owner's private method without direct calls`() {
        val snapshot = snapshot()
        val gaps = SnapshotComparison(snapshot, snapshot).analysisGaps(obligation("Dto.secret()I"))
        assertTrue(gaps.any { "Encoder.encode" in it }, gaps.toString())
    }

    @Test fun `unbounded erased payload cannot be scoped away from private method`() {
        val snapshot = snapshot("Object value")
        val gaps = SnapshotComparison(snapshot, snapshot).analysisGaps(obligation("Dto.secret()I"))
        assertTrue(gaps.any { "Encoder.encode" in it }, gaps.toString())
        assertTrue(snapshot.methodDiagnostics.values.flatten().all { it.globalObservation })
    }

    private fun snapshot(parameter: String = "Dto value"): io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot {
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        val encoder = directory.resolve("Encoder.java")
        Files.writeString(encoder, "package external; public class Encoder { public static void encode(Object value) {} }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dependency.toString(), encoder.toString()))
        val output = Files.createDirectory(directory.resolve("classes"))
        val source = directory.resolve("Sample.java")
        Files.writeString(source, """
            public class Sample {
                public int run() { return 1; }
            }
        """.trimIndent())
        val dto = directory.resolve("Dto.java")
        Files.writeString(dto, "public final class Dto { private int secret() { return 1; } }")
        val consumer = directory.resolve("Consumer.java")
        Files.writeString(consumer, "public class Consumer { public void inspect($parameter) { external.Encoder.encode(value); } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-cp", dependency.toString(), "-d", output.toString(), source.toString(), dto.toString(), consumer.toString()))
        return GraphiteAnalyzer().analyze(output, emptyList(), listOf(dependency))
    }

    private fun obligation(identity: String) = ProofObligation(identity, SymbolKind.METHOD,
        identity.substringBefore('(').substringBeforeLast('.'), emptyList())
}
