package io.johnsonlee.graphfold.engine.graphite

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * Verifies that a multi-release marker comes only from module descriptors, not alternative
 * executable classes. Descriptors with service providers remain unmodeled runtime entry points.
 * Only the input archive itself or an immediate staged archive is resolved; nested layouts and
 * arbitrary origin paths remain unknown. This certifies no other resource or artifact behavior.
 */
internal fun metadataOnlyMultiRelease(artifact: Path, origin: String): Boolean {
    val archive = stagedArchive(artifact, origin) ?: return false
    return try {
        ZipFile(archive.toFile()).use(::metadataOnlyArchive)
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IndexOutOfBoundsException) {
        false
    }
}

private fun stagedArchive(artifact: Path, origin: String): Path? {
    val simpleName = origin !in setOf("", ".", "..") && origin.none { it == '/' || it == '\\' }
    val archive = when {
        !simpleName -> null
        Files.isDirectory(artifact) -> artifact.resolve(origin)
        artifact.fileName.toString() == origin -> artifact
        else -> null
    }
    return archive?.takeIf { Files.isRegularFile(it) }
}

private fun metadataOnlyArchive(archive: ZipFile): Boolean {
    val names = mutableSetOf<String>()
    var versionedDescriptor = false
    for (entry in archive.entries()) {
        if (!names.add(entry.name) || !metadataOnlyEntry(archive, entry)) return false
        versionedDescriptor = versionedDescriptor || (!entry.isDirectory && versionedClass(entry.name))
    }
    return versionedDescriptor
}

private fun metadataOnlyEntry(archive: ZipFile, entry: ZipEntry): Boolean = when {
    entry.isDirectory -> true
    NESTED_ARCHIVE_SUFFIXES.any { entry.name.endsWith(it, ignoreCase = true) } -> false
    versionedClass(entry.name) && !versionedModulePath(entry.name) -> false
    entry.name == "module-info.class" || versionedClass(entry.name) ->
        archive.getInputStream(entry).use { moduleDescriptorWithoutProviders(it.readBytes()) }
    else -> true
}

private fun versionedClass(name: String): Boolean =
    name.startsWith("META-INF/versions/") && name.endsWith(".class", ignoreCase = true)

private fun versionedModulePath(name: String): Boolean =
    VERSIONED_MODULE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= JAVA_MODULE_VERSION } == true

private fun moduleDescriptorWithoutProviders(bytes: ByteArray): Boolean {
    val reader = ClassReader(bytes)
    if (reader.className != "module-info" || reader.access != Opcodes.ACC_MODULE ||
        reader.readUnsignedShort(CLASS_VERSION_OFFSET) < Opcodes.V9) return false
    val node = ClassNode()
    reader.accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return node.module != null && node.module.provides.orEmpty().isEmpty() &&
        node.methods.isEmpty() && node.fields.isEmpty() && node.superName == null && node.interfaces.isEmpty()
}

private val VERSIONED_MODULE = Regex("META-INF/versions/([0-9]+)/module-info\\.class")
private val NESTED_ARCHIVE_SUFFIXES = listOf(".jar", ".zip", ".war", ".ear")
private const val JAVA_MODULE_VERSION = 9
private const val CLASS_VERSION_OFFSET = 6
