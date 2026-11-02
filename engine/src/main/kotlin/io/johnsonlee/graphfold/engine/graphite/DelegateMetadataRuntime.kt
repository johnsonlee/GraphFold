package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Accept only one byte-identical definition of each adapter class from the engine's pinned stdlib. */
internal object DelegateMetadataRuntime {
    private val names = setOf("kotlin/jvm/internal/Reflection", "kotlin/jvm/internal/ReflectionFactory")

    fun trusted(dependencies: List<Path>): Boolean {
        val definitions = names.associateWith { mutableListOf<ByteArray>() }
        dependencies.forEach { path -> collect(path, definitions) }
        return definitions.all { (name, bytes) ->
            val trusted = Unit::class.java.getResourceAsStream("/$name.class")?.use { it.readBytes() }
            trusted != null && bytes.size == 1 && bytes.single().contentEquals(trusted)
        }
    }

    private fun collect(path: Path, definitions: Map<String, MutableList<ByteArray>>) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { files ->
                files.filter { Files.isRegularFile(it) }.forEach { file -> collectFile(file, definitions) }
            }
        } else collectArchive(path, definitions)
    }

    private fun collectFile(path: Path, definitions: Map<String, MutableList<ByteArray>>) {
        when {
            path.toString().endsWith(".class") -> {
                val bytes = Files.readAllBytes(path)
                definitions[ClassReader(bytes).className]?.add(bytes)
            }
            path.toString().endsWith(".jar") -> collectArchive(path, definitions)
        }
    }

    private fun collectArchive(path: Path, definitions: Map<String, MutableList<ByteArray>>) {
        ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().filter { entry ->
                names.any { entry.name == "$it.class" || entry.name.endsWith("/$it.class") }
            }.forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                definitions[ClassReader(bytes).className]?.add(bytes)
            }
        }
    }
}
