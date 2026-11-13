package io.johnsonlee.graphfold.engine.bytecode.sootup

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot

import io.johnsonlee.graphfold.engine.SnapshotComparison
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphite.input.ConstantFold
import java.net.URLClassLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaTargetsTest {
    @TempDir lateinit var directory: Path

    @Test fun `uncontrolled lazy callback change remains a finding despite equal observed output`() {
        fun version(name: String, value: Int): ProgramSnapshot {
            val root = Files.createDirectories(directory.resolve(name))
            val source = root.resolve("Sample.java")
            Files.writeString(source, """
                public class Sample {
                    public static int quote() {
                        java.util.stream.Stream.of(1).map(x -> $value);
                        return 7;
                    }
                }
                class Other { public static int changed() { return $value; } }
            """.trimIndent())
            val classes = Files.createDirectories(root.resolve("classes"))
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), source.toString()))
            URLClassLoader(arrayOf(classes.toUri().toURL())).use { loader ->
                assertEquals(7, loader.loadClass("Sample").getMethod("quote").invoke(null))
            }
            val snapshot = GraphiteAnalyzer().analyze(classes, emptyList())
            // Production methods remain comparison entries even when this traffic does not invoke them.
            return snapshot.copy(externalEntries = snapshot.methods.keys)
        }
        val base = version("base", 1)
        val head = version("head", 2)
        val obligation = ProofObligation("Sample.quote()I", SymbolKind.METHOD, "Sample", emptyList())
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation).isNotEmpty(), "Uncontrolled production callback changes must remain findings")
        assertTrue(comparison.analysisGaps(obligation).isEmpty(), "Precise ownership attributes the uncontrolled callback change")
    }

    @Test fun `only surviving factory retains its exact compiler generated implementation association`() {
        val source = directory.resolve("Sample.java")
        Files.writeString(source, """
            public class Sample {
                static boolean enabled() { return true; }
                public static Runnable run() {
                    if (enabled()) return () -> System.out.println("lambda");
                    return null;
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val analyzer = GraphiteAnalyzer()
        val plain = analyzer.analyze(classes, emptyList())
        val method = "Sample.run()Ljava/lang/Runnable;"
        val target = plain.methods.getValue(method).lambdaTargets.single()
        assertTrue(target.startsWith("Sample.lambda\$run\$"))
        assertTrue(target in plain.methods)
        val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
            "callee_class" to "Sample", "callee_name" to "enabled")), "value" to false))
        val folded = analyzer.analyze(classes, listOf(rule))
        assertEquals(listOf(1), folded.matchedRules)
        assertTrue(folded.methods.getValue(method).lambdaTargets.isEmpty())
    }
}
