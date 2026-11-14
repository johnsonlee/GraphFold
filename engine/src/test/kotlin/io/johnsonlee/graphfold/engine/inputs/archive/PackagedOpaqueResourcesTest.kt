package io.johnsonlee.graphfold.engine.inputs.archive

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagedOpaqueResourcesTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["payload.jar", "payload.zip", "payload.war", "payload.ear", "payload.JAR",
        "BOOT-INF/classes/sample/Hidden.class", "WEB-INF/classes/sample/Hidden.class",
        "BOOT-INF/config.txt", "WEB-INF/settings.txt"])
    fun `ordinary application and dependency nested resources retain inventory without entering graph`(resource: String) {
        val compiled = provenance()
        val nested = archive("payload.jar", mapOf("sample/Hidden.class" to bytecode("sample/Hidden", 99)))
        val payload = if (resource.endsWith(".class")) bytecode("sample/Hidden", 99) else Files.readAllBytes(nested)
        val app = archive("app.jar", mapOf("sample/Main.class" to bytecode("sample/Main", 1), resource to payload),
            "Main-Class: sample.Main\r\n")
        val dependency = archive("dependency.jar", mapOf(resource to payload))
        assertEquals(listOf(app.toRealPath()), manifestApplicationClasspath(app))
        URLClassLoader(arrayOf(app.toUri().toURL(), dependency.toUri().toURL()), null).use { loader ->
            assertEquals(1, loader.loadClass("sample.Main").getMethod("value").invoke(null))
            assertFailsWith<ClassNotFoundException> { loader.loadClass("sample.Hidden") }
            assertContentEquals(payload, requireNotNull(loader.getResourceAsStream(resource)).use { it.readBytes() })
        }
        fun materialize(name: String): InputProject = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(app, listOf(compiled.classes)), ArchiveEntryInput(dependency))),
            compiled, temporary.resolve(name))
        val before = materialize("before")
        assertFalse(Files.exists(before.classes.resolve("sample/Hidden.class")))
        before.dependencies.forEach { path -> JarFile(path.toFile()).use { jar ->
            assertEquals(null, jar.getJarEntry(resource))
            assertEquals(null, jar.getJarEntry("sample/Hidden.class"))
        } }
        JarFile(app.toFile()).use { assertContentEquals(payload, it.getInputStream(it.getJarEntry(resource)).readBytes()) }
        assertTrue(requireNotNull(before.production).entries.keys.any { it.endsWith(resource) })
        val changed = if (resource.endsWith(".class")) bytecode("sample/Hidden", 100) else "changed opaque payload".toByteArray()
        archive("dependency.jar", mapOf(resource to changed))
        val after = materialize("after")
        assertFalse(before.production.entries == requireNotNull(after.production).entries)
        assertFalse(before.production.nonClassEntries == after.production.nonClassEntries)
    }

    @ParameterizedTest
    @ValueSource(strings = ["WEB-INF/Helper", "BOOT-INF/classes/Helper"])
    fun `valid root classes with container looking package names remain executable`(owner: String) {
        val compiled = provenance()
        val classBytes = bytecode(owner, 7)
        val app = archive("valid-name.jar", mapOf("$owner.class" to classBytes))
        URLClassLoader(arrayOf(app.toUri().toURL()), null).use { loader ->
            assertEquals(7, loader.loadClass(owner.replace('/', '.')).getMethod("value").invoke(null))
        }
        val result = prepareArchiveInput(ArchiveInput("app", listOf(
            ArchiveEntryInput(app, listOf(compiled.classes)))), compiled, temporary.resolve("valid"))
        assertContentEquals(classBytes, Files.readAllBytes(result.classes.resolve("$owner.class")))
        val external = normalizeDependencyInputs(listOf(app), temporary.resolve("external")).single()
        JarFile(external.toFile()).use { assertContentEquals(classBytes, it.getInputStream(it.getJarEntry("$owner.class")).readBytes()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `malformed container looking class payload is rejected as unreadable input`(unsupportedVersion: Boolean) {
        val bytes = if (unsupportedVersion) bytecode("sample/Hidden", 1).also {
            it[6] = 0x7f.toByte(); it[7] = 0x7f.toByte()
        } else byteArrayOf(1, 2)
        val app = archive("malformed.jar", mapOf("WEB-INF/classes/sample/Hidden.class" to bytes))
        val failure = assertFailsWith<InputFailure> { normalizePackagedArchive(app, temporary.resolve("copy.jar")) }
        assertTrue(failure.message.contains("Cannot establish packaged class identity"))
    }

    @Test fun `ordinary dependency without a manifest retains root classes and resources when nested data is projected`() {
        val original = temporary.resolve("no-manifest.jar")
        val records = mapOf("sample/Main.class" to bytecode("sample/Main", 4),
            "settings.txt" to "outer resource".toByteArray(), "payload.zip" to byteArrayOf(1, 2))
        ZipOutputStream(Files.newOutputStream(original)).use { zip ->
            records.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val originalBytes = Files.readAllBytes(original)
        val projected = normalizePackagedArchive(original, temporary.resolve("projected.jar"))
        URLClassLoader(arrayOf(projected.toUri().toURL()), null).use { loader ->
            assertEquals(4, loader.loadClass("sample.Main").getMethod("value").invoke(null))
            assertEquals("outer resource", requireNotNull(loader.getResourceAsStream("settings.txt")).use {
                String(it.readBytes(), Charsets.UTF_8)
            })
        }
        JarFile(projected.toFile()).use { jar ->
            assertEquals(null, jar.manifest)
            assertEquals(null, jar.getJarEntry("payload.zip"))
        }
        assertContentEquals(originalBytes, Files.readAllBytes(original))
    }

    private fun provenance(): InputProject {
        val classes = Files.createDirectories(temporary.resolve("compiled"))
        Files.createDirectories(classes.resolve("sample"))
        Files.write(classes.resolve("sample/Main.class"), bytecode("sample/Main", 1))
        Files.write(classes.resolve("sample/Excluded.class"), bytecode("sample/Excluded", 3))
        val source = Files.createDirectories(temporary.resolve("src"))
        val unrelated = Files.createDirectories(temporary.resolve("unrelated"))
        return InputProject(classes, listOf(source, unrelated), emptyList(), temporary.resolve("origins"),
            sourceOutputs = mapOf(source to listOf(classes), unrelated to listOf(temporary.resolve("other-classes"))),
            compilerEvidence = listOf(CompilerEvidence(classes, temporary.resolve("own.json")),
                CompilerEvidence(temporary.resolve("other-classes"), temporary.resolve("unrelated-invalid.json"))))
    }

    private fun archive(
        name: String, entries: Map<String, ByteArray>, attributes: String = "",
    ): Path = temporary.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val files = mapOf(JarFile.MANIFEST_NAME to ("Manifest-Version: 1.0\r\n" + attributes + "\r\n").toByteArray()) + entries
            files.forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun bytecode(owner: String, value: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
