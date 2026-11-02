package io.johnsonlee.graphfold.engine.build

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * A sibling project's JAR variant can duplicate classes already merged from its compilation output.
 * Remove only exported project artifacts whose class entries are exact copies at the same JVM paths.
 * Production/runtime inventories still consume every original artifact, resource and classpath entry.
 */
internal fun gradleAnalysisDependencies(entries: List<GradleInput>, classes: Path): List<Path> {
    val outputs = entries.filter { it.kind == GradleInputKind.CLASSES }.map { it.paths.single().normalized() }.toSet()
    val artifacts = entries.filter { it.kind == GradleInputKind.ARTIFACT }.map { it.paths.single().normalized() }.toSet()
    val dependencies = entries.filter { it.kind == GradleInputKind.DEPENDENCY }.map { it.paths.single() }
        .filterNot { it.normalized() in outputs }.distinct()
    checkBuild(dependencies.all { Files.exists(it) }, "Gradle exported missing dependencies")
    return dependencies.filterNot { it.normalized() in artifacts && duplicatesApplicationClasses(it, classes.normalized()) }
}

/** Unknown layouts and unique/transformed classes stay in the classpath; no partial archive is discarded. */
private fun duplicatesApplicationClasses(artifact: Path, classes: Path): Boolean = try {
    ZipFile(artifact.toFile()).use { archive ->
        val entries = archive.entries().asSequence().toList()
        val names = entries.map { it.name }
        val classEntries = entries.filter { !it.isDirectory && it.name.endsWith(".class") }
        val ordinary = names.distinct().size == names.size && classEntries.isNotEmpty() &&
            names.none { it.substringAfterLast('.').lowercase() in NESTED_ARCHIVES }
        ordinary &&
            entries.filter { it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }.all { entry ->
                archive.getInputStream(entry).use { Manifest(it).mainAttributes.getValue(Attributes.Name.CLASS_PATH).isNullOrBlank() }
            } && classEntries.all { entry ->
                val file = classes.resolve(entry.name).normalize()
                file.startsWith(classes) && classes.relativize(file).joinToString("/") == entry.name && Files.isRegularFile(file) &&
                    archive.getInputStream(entry).use { it.readBytes() }.contentEquals(Files.readAllBytes(file))
            }
    }
} catch (_: IOException) {
    false // Preserve a non-archive or unreadable artifact for the ordinary frontend/inventory diagnostics.
}

private fun Path.normalized(): Path = toAbsolutePath().normalize()

private val NESTED_ARCHIVES = setOf("jar", "zip", "war")
