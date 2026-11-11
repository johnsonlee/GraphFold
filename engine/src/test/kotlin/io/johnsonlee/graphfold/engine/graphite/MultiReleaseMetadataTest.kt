package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiReleaseMetadataTest {
    @TempDir lateinit var directory: Path
    private val versionedModule = "META-INF/versions/9/module-info.class"

    @Test fun `ordinary Kotlin stdlib module metadata has no alternate executable class`() {
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @Test fun `module descriptors can be inspected through the staged dependency symlink`() {
        val library = archive(mapOf(versionedModule to module()))
        val staged = Files.createDirectory(directory.resolve("staged"))
        Files.createSymbolicLink(staged.resolve("dependency-0.jar"), library)
        assertTrue(metadataOnlyMultiRelease(staged, "dependency-0.jar"))
        archive(mapOf(versionedModule to module(InvalidModule.PROVIDER)))
        assertFalse(metadataOnlyMultiRelease(staged, "dependency-0.jar"), "A previous verdict must not cache changed descriptor bytes")
    }

    @Test fun `base and multiple versioned descriptors are all verified`() {
        val library = archive(mapOf(
            "META-INF/" to byteArrayOf(),
            "module-info.class" to module(),
            versionedModule to module(),
            "META-INF/versions/11/module-info.class" to module(),
            "messages.properties" to "greeting=hello".toByteArray(),
        ))
        assertTrue(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @Test fun `a base descriptor with a service provider cannot be hidden by harmless versioned metadata`() {
        val library = archive(mapOf("module-info.class" to module(InvalidModule.PROVIDER), versionedModule to module()))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".", "..", "../dependency.jar", "/dependency.jar", "nested/dependency.jar", "nested\\dependency.jar"])
    fun `arbitrary and nested artifact origins cannot authorize an exemption`(origin: String) {
        assertFalse(metadataOnlyMultiRelease(directory, origin))
    }

    @Test fun `single archive origin must name that exact input`() {
        val library = archive(mapOf(versionedModule to module()))
        assertFalse(metadataOnlyMultiRelease(library, "other.jar"))
    }

    @Test fun `missing artifact and directory are unknown`() {
        assertFalse(metadataOnlyMultiRelease(directory, "missing.jar"))
        Files.createDirectory(directory.resolve("directory.jar"))
        assertFalse(metadataOnlyMultiRelease(directory, "directory.jar"))
    }

    @Test fun `an archive without versioned descriptors does not substantiate the exemption`() {
        val library = archive(mapOf("module-info.class" to module(), "META-INF/versions/9/value.txt" to byteArrayOf()))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["META-INF/versions/9/Business.class", "META-INF/versions/8/module-info.class",
        "META-INF/versions/invalid/module-info.class", "META-INF/versions/999999999999/module-info.class",
        "META-INF/versions/9/MODULE-INFO.CLASS"])
    fun `versioned executable or unsupported class paths remain blocked`(name: String) {
        val library = archive(mapOf(versionedModule to module(), name to module()))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["nested.jar", "nested.zip", "nested.war", "nested.ear", "nested.JAR"])
    fun `nested archives cannot be certified by the outer descriptor`(name: String) {
        val library = archive(mapOf(versionedModule to module(), name to byteArrayOf()))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @ParameterizedTest
    @EnumSource(InvalidModule::class)
    fun `descriptor shape and service entries must be independently valid`(invalid: InvalidModule) {
        val library = archive(mapOf(versionedModule to module(invalid)))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @Test fun `malformed zip or truncated class cannot authorize a proof`() {
        val library = directory.resolve("dependency.jar")
        Files.write(library, byteArrayOf(1, 2, 3))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
        archive(mapOf(versionedModule to byteArrayOf(1, 2, 3)))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @Test fun `an unsupported class version remains unknown`() {
        val bytes = module()
        bytes[6] = 0x7f.toByte()
        bytes[7] = 0x7f.toByte()
        val library = archive(mapOf(versionedModule to bytes))
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    @Test fun `duplicate archive entries remain ambiguous even when the descriptors are harmless`() {
        val library = archive(mapOf(versionedModule to module(), "copy-a.txt" to byteArrayOf(), "copy-b.txt" to byteArrayOf()))
        val bytes = Files.readAllBytes(library)
        val original = "copy-b.txt".toByteArray()
        val replacement = "copy-a.txt".toByteArray()
        for (start in 0..bytes.size - original.size) {
            if (original.indices.all { bytes[start + it] == original[it] }) replacement.copyInto(bytes, start)
        }
        Files.write(library, bytes)
        assertFalse(metadataOnlyMultiRelease(library, library.fileName.toString()))
    }

    private fun archive(entries: Map<String, ByteArray>): Path = directory.resolve("dependency.jar").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun module(invalid: InvalidModule? = null): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(if (invalid == InvalidModule.OLD_VERSION) Opcodes.V1_8 else Opcodes.V9,
            if (invalid == InvalidModule.ACCESS) Opcodes.ACC_PUBLIC else Opcodes.ACC_MODULE,
            if (invalid == InvalidModule.NAME) "Other" else "module-info", null,
            if (invalid == InvalidModule.SUPERCLASS) "java/lang/Object" else null,
            if (invalid == InvalidModule.INTERFACE) arrayOf("java/io/Serializable") else null)
        if (invalid != InvalidModule.MISSING_ATTRIBUTE) {
            writer.visitModule("sample.module", 0, null).apply {
                if (invalid == InvalidModule.PROVIDER) visitProvide("sample/Service", "sample/Provider")
                visitEnd()
            }
        }
        if (invalid == InvalidModule.FIELD) writer.visitField(Opcodes.ACC_STATIC, "state", "I", null, 1).visitEnd()
        if (invalid == InvalidModule.METHOD) writer.visitMethod(Opcodes.ACC_STATIC, "work", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    enum class InvalidModule { PROVIDER, NAME, ACCESS, OLD_VERSION, MISSING_ATTRIBUTE, FIELD, METHOD, SUPERCLASS, INTERFACE }
}
