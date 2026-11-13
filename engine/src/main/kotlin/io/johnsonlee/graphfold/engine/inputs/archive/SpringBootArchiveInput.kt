package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.objectweb.asm.ClassReader

private const val BOOT_CLASSES = "BOOT-INF/classes/"
private const val BOOT_LIBRARIES = "BOOT-INF/lib/"

/** Boot layout is recognized from packaged entries, independently of the archive's launcher. */
internal fun isSpringBootArchive(path: Path): Boolean = JarFile(path.toFile()).use { jar ->
    jar.entries().asSequence().any { entry ->
        entry.name.startsWith(BOOT_LIBRARIES) || entry.name.startsWith(BOOT_CLASSES) && entry.name.endsWith(".class") &&
            jar.getInputStream(entry).use { ClassReader(it).className + ".class" == entry.name.removePrefix(BOOT_CLASSES) }
    }
}

/** Project actual packaged class bytes and libraries in stable archive-name order. */
internal fun springBootArchiveInput(input: ArchiveInput, output: Path): ArchiveInput? {
    val entry = input.classpath.singleOrNull()
    if (entry == null || !isSpringBootArchive(entry.artifact)) return null
    Files.createDirectories(output)
    val restored = entry.artifact
    return JarFile(restored.toFile()).use { jar ->
        val entries = jar.entries().asSequence().toList()
        checkInput(entries.map { it.name }.distinct().size == entries.size, "Duplicate Boot archive entry")
        val rootEntries = entries.filterNot { it.name.startsWith(BOOT_CLASSES) || it.name.startsWith(BOOT_LIBRARIES) }
        val root = bootProjection(jar, rootEntries, "", output.resolve("loader.jar"))
        val classpath = mutableListOf(ArchiveEntryInput(root))
        val nestedEntries = entries.filter(::bootClasspathEntry).sortedBy { it.name }.toMutableList()
        if (nestedEntries.none { it.name == BOOT_CLASSES } && entries.any { it.name.startsWith(BOOT_CLASSES) }) {
            nestedEntries.add(0, JarEntry(BOOT_CLASSES))
        }
        nestedEntries.forEachIndexed { index, nested ->
            val path = output.resolve("entry-$index.jar")
            if (nested.name == BOOT_CLASSES) {
                bootProjection(jar, entries.filter { it.name.startsWith(BOOT_CLASSES) }, BOOT_CLASSES, path)
                classpath += ArchiveEntryInput(path, entry.producerOutputs, application = true)
            } else {
                jar.getInputStream(nested).use { Files.copy(it, path) }
                val producers = input.producerArchives.filterKeys { Files.isRegularFile(it) && digest(it) == digest(path) }.values.flatten()
                classpath += ArchiveEntryInput(path, producers,
                    application = input.ownsApplicationArchive(path))
            }
        }
        input.copy(classpath = classpath + input.staticDependencies.map { ArchiveEntryInput(it) },
            archiveMetadata = input.archiveMetadata + mapOf("kind" to "spring-boot"))
    }
}

private fun bootClasspathEntry(entry: JarEntry): Boolean =
    entry.isDirectory && entry.name == BOOT_CLASSES ||
        !entry.isDirectory && entry.name.startsWith(BOOT_LIBRARIES) && entry.name.endsWith(".jar")

private fun bootProjection(jar: JarFile, entries: List<JarEntry>, prefix: String, output: Path): Path {
    JarOutputStream(Files.newOutputStream(output)).use { target ->
        entries.filter { !it.isDirectory }.forEach { entry ->
            val name = entry.name.removePrefix(prefix)
            checkInput(name.isNotEmpty() && !name.startsWith('/') && name.split('/').none { it == ".." },
                "Invalid Boot packaged entry: ${entry.name}")
            target.putNextEntry(JarEntry(name).apply { time = 0L })
            jar.getInputStream(entry).use { it.copyTo(target) }
            target.closeEntry()
        }
    }
    return output
}
