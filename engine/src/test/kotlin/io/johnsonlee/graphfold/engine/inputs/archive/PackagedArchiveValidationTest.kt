package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackagedArchiveValidationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `duplicate packaged resource names fail before projection can select a different entry`() {
        val archive = archive(mapOf("a.txt" to byteArrayOf(1), "b.txt" to byteArrayOf(2)))
        val bytes = Files.readAllBytes(archive)
        val name = "b.txt".toByteArray()
        (0..bytes.size - name.size).filter { offset -> name.indices.all { bytes[offset + it] == name[it] } }
            .forEach { bytes[it] = 'a'.code.toByte() }
        Files.write(archive, bytes)
        assertTrue(assertFailsWith<InputFailure> {
            normalizePackagedArchive(archive, temporary.resolve("normalized.jar"))
        }.message.contains("Duplicate packaged archive entry"))
    }

    @Test fun `ordinary jar version directories do not become active resource bundle entries`() {
        val source = temporary.resolve("Main.java")
        Files.writeString(source, "public class Main { public static void main(String[] args) {} }")
        val classes = Files.createDirectories(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-d", classes.toString(), source.toString()))
        val archive = archive(mapOf("Main.class" to Files.readAllBytes(classes.resolve("Main.class")),
            "config.properties" to "value=base".toByteArray(),
            "META-INF/versions/17/config.properties" to "value=inactive".toByteArray(),
            "META-INF/versions/17/hidden.properties" to "value=inactive".toByteArray(),
            "resources/" to byteArrayOf()))
        val provenance = InputProject(classes, listOf(temporary), emptyList(), sourceOutputs = mapOf(temporary to listOf(classes)))
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(archive, listOf(classes)))), provenance, temporary.resolve("prepared"))
        assertEquals(mapOf("config.properties" to mapOf("value" to "base")), prepared.production!!.resourceEntries)
    }

    private fun archive(entries: Map<String, ByteArray>): Path = temporary.resolve("application.jar").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val files = mapOf(JarFile.MANIFEST_NAME to "Manifest-Version: 1.0\r\n\r\n".toByteArray()) + entries
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
}
