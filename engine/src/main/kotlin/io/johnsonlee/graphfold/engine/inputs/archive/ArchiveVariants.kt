package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile

/** An explicit archive variant; no host JVM version participates in selection. */
internal fun staticArchiveEntries(archive: JarFile, threshold: Int): Map<String, JarEntry> {
    val physical = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
    val selected = physical.filterNot { it.name.startsWith("META-INF/versions/") }.associateBy { it.name }.toMutableMap()
    if (archive.manifest?.mainAttributes?.getValue(Attributes.Name.MULTI_RELEASE).equals("true", ignoreCase = true)) {
        physical.mapNotNull { entry -> archiveVariant(entry.name)?.let { (version, name) -> Triple(version, name, entry) } }
            .filter { it.first <= threshold }.sortedBy { it.first }.forEach { (_, name, entry) -> selected[name] = entry }
    }
    return selected
}

internal fun archiveVariant(name: String): Pair<Int, String>? = VERSIONED_ENTRY.matchEntire(name)?.let {
    it.groupValues[1].toIntOrNull()?.let { version -> version to it.groupValues[2] }
}

private val VERSIONED_ENTRY = Regex("""META-INF/versions/([1-9][0-9]*)/(.+)""")

/** Inventory and graphs see the same build-selected MR contents, including effective resource entries. */
internal fun selectedArchiveInventory(archive: Path, javaVersion: Int, output: Path): Path =
    JarFile(archive.toFile()).use { jar ->
        val multiRelease = jar.manifest?.mainAttributes?.getValue(Attributes.Name.MULTI_RELEASE)
        if (!multiRelease.equals("true", ignoreCase = true)) return@use archive
        Files.createDirectories(output.parent)
        JarOutputStream(Files.newOutputStream(output)).use { target ->
            staticArchiveEntries(jar, javaVersion).forEach { (name, entry) ->
                target.putNextEntry(JarEntry(name))
                copyArchiveEntry(jar, entry, target)
                target.closeEntry()
            }
        }
        output
    }

private fun copyArchiveEntry(jar: JarFile, entry: JarEntry, target: JarOutputStream) {
    jar.getInputStream(entry).use { it.copyTo(target) }
}
