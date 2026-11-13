package io.johnsonlee.graphfold.engine.inputs.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader

/** Resolve a selected JAR's declared archive search path without requiring an executable entrypoint. */
internal fun manifestApplicationClasspath(primary: Path): List<Path> =
    expandManifestClasspath(listOf(primary.toAbsolutePath().normalize()))

/** Manifest Class-Path archives are expanded at their owning archive's position, before later classpath entries. */
internal fun expandManifestClasspath(classpath: List<Path>): List<Path> {
    val selected = linkedSetOf<Path>()
    fun visit(path: Path) {
        val archive = canonicalPackagedArchive(path)
        if (selected.add(archive)) {
            val references = JarFile(archive.toFile()).use {
                it.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH).orEmpty()
            }
            references.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { reference ->
                val resolved = archive.toUri().resolve(URI(reference))
                checkInput(resolved.scheme == "file", "Unreadable non-local manifest Class-Path archive: $resolved")
                visit(Path.of(resolved))
            }
        }
    }
    classpath.forEach(::visit)
    return selected.toList()
}

/** Preserve original artifacts for inventory; the analysis copy omits Class-Path after its references have been expanded. */
internal fun normalizePackagedArchive(path: Path, output: Path): Path = JarFile(path.toFile()).use { jar ->
    val entries = jar.entries().asSequence().toList()
    checkInput(entries.map { it.name }.distinct().size == entries.size, "Duplicate packaged archive entry: $path")
    val retained = entries.filterNot { entry ->
        opaquePackagedResource(entry.name) { jar.getInputStream(entry).use { it.readBytes() } }
    }
    val manifest = jar.manifest
    val removedClasspath = manifest?.mainAttributes?.remove(Attributes.Name.CLASS_PATH)
    if (removedClasspath == null && retained.size == entries.size) return@use path
    val encoded = manifest?.let { value -> ByteArrayOutputStream().also { value.write(it) }.toByteArray() }
    Files.createDirectories(output.parent)
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        retained.forEach { entry -> copyPackagedEntry(jar, zip, entry, encoded) }
    }
    output
}

private fun copyPackagedEntry(jar: JarFile, zip: ZipOutputStream, entry: JarEntry, manifest: ByteArray?) {
    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
    if (manifest != null && entry.name.equals(JarFile.MANIFEST_NAME, ignoreCase = true)) zip.write(manifest)
    else jar.getInputStream(entry).use { it.copyTo(zip) }
    zip.closeEntry()
}

/** Manifest relatives resolve from the physical archive, including a symlinked selected JAR. */
internal fun canonicalPackagedArchive(path: Path): Path {
    checkInput(Files.isRegularFile(path), "Missing packaged archive: $path")
    try {
        JarFile(path.toFile()).close()
    } catch (failure: IOException) {
        throw InputFailure("Unreadable packaged archive $path: $failure").also { it.initCause(failure) }
    }
    return path.toRealPath()
}

/** Nested archives and misplaced class entries are archive resources, never additional graph archives or class roots. */
internal fun opaquePackagedResource(name: String, bytes: () -> ByteArray): Boolean = when {
    name.substringAfterLast('.').lowercase() in setOf("jar", "war", "ear", "zip") -> true
    !name.startsWith("BOOT-INF/") && !name.startsWith("WEB-INF/") -> false
    !name.endsWith(".class") -> true
    else -> packagedClassName(name, bytes()) + ".class" != name
}

private fun packagedClassName(name: String, bytes: ByteArray): String = try {
    ClassReader(bytes).className
} catch (failure: IllegalArgumentException) {
    throw invalidPackagedClass(name, failure)
} catch (expected: ArrayIndexOutOfBoundsException) {
    // ASM reports truncated or malformed constant-pool input through this specific exception.
    throw invalidPackagedClass(name, expected)
}

private fun invalidPackagedClass(name: String, failure: RuntimeException): InputFailure =
    InputFailure("Cannot establish packaged class identity for $name: $failure").also { it.initCause(failure) }
