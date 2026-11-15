package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

/** Per-analysis reuse only: content, prior class definitions and application copies all participate in the key. */
internal class DependencyInputCache {
    private data class Input(val path: Path, val digest: String, val variant: Int)
    private data class Key(
        val input: Input, val shadowed: Map<String, String>, val application: Map<String, String>, val classOrder: StaticClassOrder,
    )
    private data class Projection(val path: Path, val introduced: Map<String, String>)
    private val names = mutableMapOf<Input, Set<String>>()
    private val projections = mutableMapOf<Key, Projection>()

    fun materialize(
        dependency: Path, variantThreshold: Int, application: Path?, selected: MutableMap<String, String>,
        classOrder: StaticClassOrder, produce: () -> Path,
    ): Path {
        val path = dependency.toAbsolutePath().normalize()
        val input = Input(path, digest(path), variantThreshold)
        val classes = names.getOrPut(input) { classNames(path, variantThreshold) }
        val root = application?.toAbsolutePath()?.normalize()
        val copies = classes.mapNotNull { name ->
            root?.resolve(name)?.normalize()?.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
                ?.let { name to digest(it) }
        }.toMap()
        val key = Key(input, selected.filterKeys { it in classes }, copies, classOrder)
        val projection = projections.getOrPut(key) {
            val before = selected.keys.toSet()
            Projection(produce(), selected.filterKeys { it !in before })
        }
        selected.putAll(projection.introduced)
        return projection.path
    }

    private fun classNames(path: Path, variant: Int): Set<String> {
        val entries = if (Files.isDirectory(path)) regularFiles(path).map { path.relativize(it).joinToString("/") }
        else JarFile(path.toFile()).use { archive ->
            staticArchiveEntries(archive, variant).keys.toList()
        }
        return entries.filter { it.endsWith(".class") && it != "module-info.class" && !it.startsWith("META-INF/versions/") }.toSet()
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (Files.isDirectory(path)) regularFiles(path).sorted().forEach { file ->
            val name = path.relativize(file).joinToString("/").toByteArray(Charsets.UTF_8)
            digest.update(name)
            digest.update(0.toByte())
            val contents = MessageDigest.getInstance("SHA-256")
            update(contents, file)
            digest.update(contents.digest())
        } else update(digest, path)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(digest: MessageDigest, path: Path) {
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = input.read(buffer)
            while (size >= 0) {
                digest.update(buffer, 0, size)
                size = input.read(buffer)
            }
        }
    }
}
