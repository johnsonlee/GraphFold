package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.build.ProductionInventory
import io.johnsonlee.graphfold.engine.origin.OriginCatalog
import io.johnsonlee.graphfold.engine.origin.OriginProject
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphiteAnalyzerBoundaryTest {
    @TempDir lateinit var directory: Path

    @Test fun `origin analysis retains missing class references rather than trusting normalized owner evidence`() {
        val classes = compile("missing", "return \"ok\";", "public Missing dependency; } class Missing {")
        Files.delete(classes.resolve("Missing.class"))
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(project(classes), emptyList())
        assertTrue(snapshot.diagnostics.any { it == "Unresolved class reference: Missing" }, snapshot.diagnostics.toString())
    }

    @Test fun `general selection refuses a graph whose native body cannot support the query`() {
        val classes = compile("native", "return nativeValue();", "public static native String nativeValue();")
        val rule = io.johnsonlee.graphite.input.FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) RETURN cs LIMIT 1", "value" to false))
        val failure = assertFailsWith<IllegalArgumentException> { GraphiteAnalyzer().analyze(classes, listOf(rule)) }
        assertTrue("NATIVE" in failure.message.orEmpty(), failure.message)
    }

    @Test fun `APK inputs are explicitly rejected before the unsupported frontend is entered`() {
        val apk = Files.write(directory.resolve("application.APK"), byteArrayOf())
        val analyzer = GraphiteAnalyzer()
        val failure = assertFailsWith<IllegalArgumentException> { analyzer.analyze(apk, emptyList()) }
        assertTrue("APK analysis is unsupported" in failure.message.orEmpty())
        val classes = compile("classes")
        val project = project(classes)
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeOriginProject(project.copy(compiled = project.compiled.copy(classes = apk)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeOriginProject(project.copy(sourceClasses = apk), emptyList())
        }
    }

    @Test fun `a directory named apk still contains ordinary JVM classes`() {
        val classes = compile("classes.apk")
        val snapshot = GraphiteAnalyzer().analyze(classes, emptyList())
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue("Sample.run()Ljava/lang/String;" in snapshot.methods)
    }

    @Test fun `origin analysis supports resource proofs with and without exported production metadata`() {
        val classes = compile("classes")
        val base = project(classes)
        val variants = listOf(base, base.copy(compiled = base.compiled.copy(
            production = ProductionInventory(emptyMap(), emptyMap()))))
        variants.forEach { project ->
            val snapshot = GraphiteAnalyzer().analyzeOriginProject(project, emptyList(), dependencyResources = true)
            assertTrue(snapshot.resourceDependenciesAnalyzed, snapshot.diagnostics.toString())
            assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
            assertEquals(mapOf("Sample" to "Sample"), snapshot.originalOwners)
        }
    }

    @Test fun `origin annotations merge with existing reflection gaps rather than replacing them`() {
        val classes = compile("reflection", "Sample.class.getDeclaredMethods(); return \"ok\";")
        val identity = "Sample.run()Ljava/lang/String;"
        val project = project(classes).copy(methodDiagnostics = mapOf(identity to listOf(MethodAnalysisGap("origin evidence"))))
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(project, emptyList())
        val gaps = snapshot.methodDiagnostics.getValue(identity)
        assertTrue(gaps.any { it.message == "origin evidence" })
        assertTrue(gaps.any { it.message != "origin evidence" }, gaps.toString())
    }

    private fun project(classes: Path) = OriginProject(CompiledProject(classes, emptyList(), emptyList()), classes,
        OriginCatalog(emptyList(), emptyList()), mapOf("Sample" to "Sample"))

    private fun compile(name: String, body: String = "return \"ok\";", declarations: String = ""): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Sample.java")
        Files.writeString(source, "public class Sample { public String run() { $body } $declarations }")
        val classes = Files.createDirectories(root.resolve("output.apk"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }
}
