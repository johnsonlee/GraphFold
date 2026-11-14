package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun readWarArchive(path: Path): Map<String, ByteArray> = JarFile(path.toFile()).use { jar ->
    val entries = jar.entries().asSequence().filterNot { it.isDirectory }.toList()
    checkInput(entries.map { it.name }.distinct().size == entries.size, "Duplicate WAR entry: $path")
    entries.associate { entry ->
        val normalized = Path.of(entry.name).normalize()
        checkInput(!normalized.isAbsolute && !normalized.startsWith("..") && normalized.joinToString("/") == entry.name,
            "Invalid WAR entry ${entry.name}: $path")
        entry.name to jar.getInputStream(entry).use { it.readBytes() }
    }
}

internal fun writeAnalysisArchive(path: Path, entries: Map<String, ByteArray>): Path {
    Files.createDirectories(path.parent)
    ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.toSortedMap().forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
    } }
    return path
}

internal fun archiveContentIdentity(entries: Map<String, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.toSortedMap().forEach { (name, bytes) ->
        digest.update("${name.length}:$name:${bytes.size}:".toByteArray())
        digest.update(bytes)
    }
    return HexFormat.of().formatHex(digest.digest())
}
