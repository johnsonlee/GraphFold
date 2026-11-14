package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedArchiveProjectionTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("jar,8", "jar,17", "boot,8", "boot,17", "war,8", "war,17")
    fun `signed MR packaged inputs retain evidence and select only the build JDK`(layout: String, javaVersion: Int) {
        val library = signedArchive(multiRelease = true)
        val original = Files.readAllBytes(library)
        val archive = when (layout) {
            "boot" -> projectionArchive(temporary.resolve("app.jar"), mapOf("BOOT-INF/lib/library.jar" to original))
            "war" -> projectionArchive(temporary.resolve("app.war"), mapOf("WEB-INF/lib/library.jar" to original))
            else -> library
        }
        val input = InputProject(Files.createDirectory(temporary.resolve("compiler")), emptyList(), emptyList())
        val result = prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(archive))), input,
            temporary.resolve("prepared"), variantThreshold = javaVersion)
        val expected = bytecode(if (javaVersion < 17) 1 else 2)
        val owned = result.classes.resolve("sample/Value.class")
        val observed = if (Files.exists(owned)) Files.readAllBytes(owned) else result.dependencies.firstNotNullOf { path ->
            JarFile(path.toFile()).use { jar -> jar.getJarEntry("sample/Value.class")?.let { jar.getInputStream(it).readBytes() } }
        }
        assertContentEquals(expected, observed)
        result.dependencies.forEach(::readVerified)
        assertContentEquals(original, Files.readAllBytes(library))
        val resources = requireNotNull(result.production).entries
        ZipFile(library.toFile()).use { jar ->
            jar.entries().asSequence().filter { archiveSignature(it.name) || it.name == JarFile.MANIFEST_NAME }.forEach { entry ->
                val hash = sha(jar.getInputStream(entry).use { it.readBytes() })
                assertTrue(resources.any { (name, value) -> name.endsWith("/${entry.name}") && value == hash }, entry.name)
            }
        }
    }

    @Test fun `direct signed MR dependency projects without stale signing blocks`() {
        val library = signedArchive(multiRelease = true)
        val original = Files.readAllBytes(library)
        val output = normalizeDependencyInputs(listOf(library), temporary.resolve("normalized"), variantThreshold = 17).single()
        assertUnsignedSelected(output, bytecode(2))
        assertContentEquals(original, Files.readAllBytes(library))
        readVerified(library)
    }

    @Test fun `unchanged signed ordinary dependency retains the original signed archive`() {
        val library = signedArchive(multiRelease = false)
        assertEquals(library, selectedArchiveInventory(library, 17, temporary.resolve("inventory/view.jar")))
        assertEquals(library, normalizeDependencyInputs(listOf(library), temporary.resolve("normalized"), variantThreshold = 17).single())
        readVerified(library)
        JarFile(library.toFile()).use { jar ->
            val entry = jar.getJarEntry("sample/Value.class")
            jar.getInputStream(entry).use { it.readBytes() }
            assertTrue(entry.codeSigners.isNotEmpty())
        }
    }

    @Test fun `manifest projection removes signing blocks but keeps original evidence`() {
        val library = signedArchive(multiRelease = false, attributes = mapOf("Class-Path" to "other.jar"))
        val original = Files.readAllBytes(library)
        val output = normalizePackagedArchive(library, temporary.resolve("normalized/library.jar"))
        assertUnsignedSelected(output, bytecode(1))
        assertContentEquals(original, Files.readAllBytes(library))
        readVerified(library)
    }

    @Test fun `projected exploded signed archive omits stale signing blocks`() {
        val library = signedArchive(multiRelease = true)
        val directory = Files.createDirectory(temporary.resolve("exploded"))
        ZipFile(library.toFile()).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val target = directory.resolve(entry.name)
                Files.createDirectories(target.parent)
                jar.getInputStream(entry).use { Files.copy(it, target) }
            }
        }
        val result = normalizeDependencyInputs(listOf(directory), temporary.resolve("normalized")).single()
        assertUnsignedSelected(result, bytecode(1))
        assertTrue(Files.exists(directory.resolve("META-INF/TEST.SF")))
    }

    @Test fun `tampered signed source is rejected before a projection can hide it`() {
        val library = signedArchive(multiRelease = true)
        val entries = ZipFile(library.toFile()).use { jar -> jar.entries().asSequence().associate { entry ->
            entry.name to jar.getInputStream(entry).use { it.readBytes() }
        } }.toMutableMap()
        entries["META-INF/versions/17/sample/Value.class"] = bytecode(3)
        val tampered = projectionArchive(temporary.resolve("tampered.jar"), entries)
        assertFailsWith<SecurityException> {
            selectedArchiveInventory(tampered, 17, temporary.resolve("inventory/view.jar"))
        }
        assertFailsWith<SecurityException> {
            normalizeDependencyInputs(listOf(tampered), temporary.resolve("normalized"), variantThreshold = 17)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["META-INF/KEY.SF", "meta-inf/key.rsa", "META-INF/KEY.DSA", "META-INF/KEY.EC", "META-INF/SIG-CUSTOM"])
    fun `root signing blocks are omitted from graph projections`(name: String) {
        assertTrue(archiveSignature(name))
    }

    @ParameterizedTest
    @ValueSource(strings = ["OTHER/KEY.SF", "META-INF/services/KEY.SF", "META-INF/MANIFEST.MF", "META-INF/key.txt"])
    fun `ordinary resources are not signing blocks`(name: String) {
        assertFalse(archiveSignature(name))
    }

    private fun signedArchive(multiRelease: Boolean, attributes: Map<String, String> = emptyMap()): Path {
        val entries = linkedMapOf("sample/Value.class" to bytecode(1), "settings.properties" to "value=1\n".toByteArray())
        if (multiRelease) entries["META-INF/versions/17/sample/Value.class"] = bytecode(2)
        val manifest = if (multiRelease) attributes + ("Multi-Release" to "true") else attributes
        val archive = projectionArchive(temporary.resolve("library.jar"), entries, manifest)
        val key = temporary.resolve("test.p12")
        runTool("keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=GraphFold test", "-storetype", "PKCS12", "-keystore", key.toString(), "-storepass", "testpass", "-noprompt")
        runTool("jarsigner", "-keystore", key.toString(), "-storepass", "testpass", archive.toString(), "test")
        readVerified(archive)
        return archive
    }

    private fun runTool(tool: String, vararg arguments: String) {
        val executable = Path.of(System.getProperty("java.home"), "bin", tool)
        val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "$tool: $output")
    }

    private fun readVerified(path: Path) = JarFile(path.toFile()).use { jar ->
        jar.entries().asSequence().filterNot { it.isDirectory }.forEach { jar.getInputStream(it).use { input -> input.readBytes() } }
    }

    private fun assertUnsignedSelected(path: Path, expected: ByteArray) = JarFile(path.toFile()).use { jar ->
        assertFalse(jar.entries().asSequence().any { archiveSignature(it.name) })
        assertContentEquals(expected, jar.getInputStream(jar.getJarEntry("sample/Value.class")).use { it.readBytes() })
        readVerified(path)
    }

    private fun bytecode(value: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Value", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
