package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PackagedJvmLookupTest {
    @TempDir lateinit var temporary: Path

    @Test fun `explicit archive variant projects its actual classes and resources`() {
        val old = compile("old", mapOf("sample/Value.java" to value("base")))
        val selected = compile("selected", mapOf("sample/Value.java" to value("selected")))
        val library = archive("library.jar", mapOf(
            "sample/Value.class" to Files.readAllBytes(old.resolve("sample/Value.class")),
            "META-INF/versions/17/sample/Value.class" to Files.readAllBytes(selected.resolve("sample/Value.class")),
            "message.txt" to "base-resource".toByteArray(),
            "META-INF/versions/17/message.txt" to "selected-resource".toByteArray()), "Multi-Release: true\r\n")
        val app = compile("app", mapOf("sample/Main.java" to """
            package sample;
            public class Main {
                public static void main(String[] args) throws Exception {
                    try (java.io.InputStream in = Main.class.getClassLoader().getResourceAsStream("message.txt")) {
                        System.out.print(Value.text() + ":" + new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
        """.trimIndent()), library)
        val packaged = archive("app.jar", classEntries(app), "Main-Class: sample.Main\r\nClass-Path: library.jar\r\n")
        val prepared = prepareArchiveInput(ArchiveInput("app", manifestApplicationClasspath(packaged).map {
            ArchiveEntryInput(it, if (it == packaged.toRealPath()) listOf(app) else emptyList())
        }), provenance(app), temporary.resolve("projection"), variantThreshold = 17)
        assertContentEquals(Files.readAllBytes(app.resolve("sample/Main.class")),
            Files.readAllBytes(prepared.classes.resolve("sample/Main.class")))
        JarFile(prepared.dependencies.last().toFile()).use { jar ->
            assertContentEquals(Files.readAllBytes(selected.resolve("sample/Value.class")),
                jar.getInputStream(jar.getJarEntry("sample/Value.class")).readBytes())
        }
    }

    @Test fun `conflicting archive definitions cannot hide behind classpath order`() {
        val external = compile("external", mapOf("sample/Value.java" to value("external-first")))
        val externalJar = archive("external.jar", classEntries(external))
        val app = compile("app", mapOf("sample/Value.java" to value("application-later"),
            "sample/Main.java" to """
                package sample;
                public class Main { public static void main(String[] a) { System.out.print(Value.text()); } }
            """.trimIndent()))
        val appJar = archive("app.jar", classEntries(app))
        val failure = assertFailsWith<InputFailure> {
            prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(externalJar),
                ArchiveEntryInput(appJar, listOf(app)))), provenance(app), temporary.resolve("projection"))
        }
        assertTrue(failure.message.contains("Ambiguous static class definition"))
    }

    @Test fun `manifest dependencies resolve beside the physical archive target`() {
        Files.createDirectories(temporary.resolve("stored"))
        Files.createDirectories(temporary.resolve("public"))
        val stored = compile("stored-value", mapOf("sample/Value.java" to value("physical-target")))
        val publicClasses = compile("public-value", mapOf("sample/Value.java" to value("symlink-directory")))
        val storedLibrary = archive("stored/library.jar", classEntries(stored))
        archive("public/library.jar", classEntries(publicClasses))
        val app = compile("app", mapOf("sample/Main.java" to """
            package sample;
            public class Main { public static void main(String[] a) { System.out.print(Value.text()); } }
        """.trimIndent()), storedLibrary)
        val packaged = archive("stored/app.jar", classEntries(app),
            "Main-Class: sample.Main\r\nClass-Path: library.jar\r\n")
        val linked = Files.createSymbolicLink(temporary.resolve("public/app.jar"), packaged)
        val actual = manifestApplicationClasspath(linked)
        assertEquals(listOf(packaged.toRealPath(), storedLibrary.toRealPath()), actual)
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(linked, listOf(app)), ArchiveEntryInput(storedLibrary))), provenance(app),
            temporary.resolve("projection"))
        JarFile(prepared.dependencies.last().toFile()).use { jar ->
            assertContentEquals(Files.readAllBytes(stored.resolve("sample/Value.class")),
                jar.getInputStream(jar.getJarEntry("sample/Value.class")).readBytes())
        }
    }

    private fun provenance(classes: Path): InputProject = InputProject(classes, listOf(classes.parent), emptyList(),
        sourceOutputs = mapOf(classes.parent to listOf(classes)))

    private fun value(text: String): String = "package sample; public class Value { public static String text() { return \"$text\"; } }"

    private fun compile(name: String, sources: Map<String, String>, dependency: Path? = null): Path {
        val directory = Files.createDirectories(temporary.resolve(name))
        val output = Files.createDirectories(directory.resolve("classes"))
        val files = sources.map { (path, text) ->
            val source = directory.resolve(path)
            Files.createDirectories(source.parent)
            Files.writeString(source, text).toString()
        }
        val arguments = listOf("--release", "17", "-d", output.toString()) +
            dependency?.let { listOf("-cp", it.toString()) }.orEmpty() + files
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return output
    }

    private fun classEntries(directory: Path): Map<String, ByteArray> = regularFiles(directory).associate {
        directory.relativize(it).joinToString("/") to Files.readAllBytes(it)
    }

    private fun archive(
        name: String, entries: Map<String, ByteArray>, attributes: String = "",
    ): Path = temporary.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val files = mapOf(JarFile.MANIFEST_NAME to ("Manifest-Version: 1.0\r\n" + attributes + "\r\n").toByteArray()) + entries
            files.forEach { (entry, bytes) -> zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry() }
        }
    }
}
