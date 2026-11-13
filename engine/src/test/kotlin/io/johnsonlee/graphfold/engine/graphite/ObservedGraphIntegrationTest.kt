package io.johnsonlee.graphfold.engine.graphite


import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ObservedGraphIntegrationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `folding a dependency retains only the selected resource key and application identities`(enabled: Boolean) {
        val fixture = fixture(archive = true)
        val snapshot = analyze(fixture, enabled)

        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue(snapshot.resourceDependenciesAnalyzed)
        assertEquals(listOf(2), snapshot.matchedRules)
        assertEquals(setOf(ResourceRead("messages", if (enabled) "treatment" else "control")), reads(snapshot),
            snapshot.methodDiagnostics.toString())
        assertEquals(setOf("app.Entry"), snapshot.entryClasses)
        assertEquals(setOf("app.Entry"), snapshot.resourceEntryReads.keys)
        assertTrue(snapshot.methods.isNotEmpty())
        assertTrue(snapshot.methods.keys.all { it.startsWith("app.Entry.") }, snapshot.methods.keys.toString())
        assertFalse(snapshot.declarations.keys.any { it.startsWith("external.") }, snapshot.declarations.keys.toString())
    }

    @Test fun `an unused native dependency declaration remains a local gap without poisoning resource reads`() {
        val fixture = fixture(archive = false)
        val snapshot = GraphiteAnalyzer().analyze(fixture.application, emptyList(), listOf(fixture.dependency), true)

        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertTrue(snapshot.resourceDependenciesAnalyzed)
        assertEquals(setOf(ResourceRead("messages", "control"), ResourceRead("messages", "treatment")), reads(snapshot))
        assertTrue(snapshot.methodDiagnostics.getValue("external.Reader.unusedNative()Ljava/lang/String;")
            .any { it.message.contains(AnalysisStatus.NATIVE.name) })
        assertFalse(snapshot.methods.containsKey("external.Reader.unusedNative()Ljava/lang/String;"))
    }

    @Test fun `native resource uncertainty follows the surviving dependency call after folding`() {
        val fixture = fixture(archive = true)
        val foldedAway = analyze(fixture, false)
        val surviving = analyze(fixture, true)
        val entry = "app.Entry.guardedNative()Ljava/lang/String;"

        assertTrue(foldedAway.diagnostics.isEmpty(), foldedAway.diagnostics.toString())
        assertTrue(surviving.diagnostics.isEmpty(), surviving.diagnostics.toString())
        assertTrue(foldedAway.resourceDependenciesAnalyzed)
        assertTrue(surviving.resourceDependenciesAnalyzed)
        assertTrue(foldedAway.methods.getValue(entry).resourceReads.isEmpty(), foldedAway.toString())
        assertTrue(ResourceRead(null, null) in surviving.methods.getValue(entry).resourceReads, surviving.toString())
        // The live native path belongs to the other entry, not the ordinary resource-reading method.
        assertEquals(setOf(ResourceRead("messages", "treatment")), reads(surviving))
    }

    private fun analyze(fixture: Fixture, enabled: Boolean): ProgramSnapshot {
        val rule = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite {callee_class: 'external.Reader', callee_name: 'enabled'}) RETURN cs",
            "value" to enabled,
        ))
        return GraphiteAnalyzer().analyze(fixture.application, listOf(rule), listOf(fixture.dependency), true)
    }

    private fun reads(snapshot: ProgramSnapshot): Set<ResourceRead> =
        snapshot.methods.getValue("app.Entry.read()Ljava/lang/String;").resourceReads.toSet()

    private fun fixture(archive: Boolean): Fixture {
        val dependencySource = directory.resolve("Reader.java")
        Files.writeString(dependencySource, """
            package external;
            public final class Reader {
                private Reader() {}
                public static boolean enabled() { return true; }
                public static native String nativeRead();
                public static native String unusedNative();
                public static String read() {
                    java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("messages");
                    if (enabled()) return bundle.getString("treatment");
                    return bundle.getString("control");
                }
                public static String guardedNative() {
                    if (enabled()) return nativeRead();
                    return "safe";
                }
            }
        """.trimIndent())
        val dependencyClasses = Files.createDirectory(directory.resolve("dependency"))
        compile(dependencySource, dependencyClasses)
        val dependency = if (archive) archive(dependencyClasses) else dependencyClasses
        val applicationSource = directory.resolve("Entry.java")
        Files.writeString(applicationSource, """
            package app;
            public final class Entry {
                public String read() { return external.Reader.read(); }
                public String guardedNative() { return external.Reader.guardedNative(); }
            }
        """.trimIndent())
        val application = Files.createDirectory(directory.resolve("application"))
        compile(applicationSource, application, dependency)
        return Fixture(application, dependency)
    }

    private fun compile(source: Path, output: Path, dependency: Path? = null) {
        val arguments = mutableListOf("-g", "-d", output.toString())
        dependency?.let { arguments.addAll(listOf("-classpath", it.toString())) }
        arguments.add(source.toString())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
    }

    private fun archive(classes: Path): Path {
        val archive = directory.resolve("reader.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            Files.walk(classes).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    jar.putNextEntry(JarEntry(classes.relativize(file).joinToString("/")))
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }
        return archive
    }

    private data class Fixture(val application: Path, val dependency: Path)
}
