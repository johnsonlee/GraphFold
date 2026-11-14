package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archive images exercise static input projection independently of startup or observed execution. */
internal fun projectionArchive(path: Path, entries: Map<String, ByteArray>, attributes: Map<String, String> = emptyMap()): Path {
    Files.createDirectories(path.parent)
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        if (attributes.isNotEmpty()) {
            val manifest = Manifest()
            manifest.mainAttributes.putValue("Manifest-Version", "1.0")
            attributes.forEach { (key, value) -> manifest.mainAttributes.putValue(key, value) }
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            manifest.write(zip)
            zip.closeEntry()
        }
        entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }
    return path
}

internal fun projectionBytes(vararg entries: Pair<String, String>): Map<String, ByteArray> =
    entries.associate { it.first to it.second.toByteArray() }

internal fun duplicateProjectionEntry(path: Path) {
    val bytes = Files.readAllBytes(path)
    val from = "other.txt".toByteArray()
    val to = "first.txt".toByteArray()
    for (index in 0..bytes.size - from.size) {
        if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
    }
    Files.write(path, bytes)
}
