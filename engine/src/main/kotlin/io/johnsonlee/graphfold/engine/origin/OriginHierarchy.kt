package io.johnsonlee.graphfold.engine.origin

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

internal enum class OriginSubtype { YES, NO, UNKNOWN }

/** Read the compilation's dependency bytes; never load or initialize application classes. */
internal class OriginHierarchy(nodes: List<ClassNode>, private val dependencies: List<Path>) {
    private val definitions = nodes.associateBy { it.name }.toMutableMap()
    private val absent = mutableSetOf<String>()

    fun serializable(owner: String): OriginSubtype = subtype(owner, "java/io/Serializable")

    fun subtype(owner: String, target: String): OriginSubtype = subtype(owner, target, mutableSetOf())

    private fun subtype(owner: String, target: String, visiting: MutableSet<String>): OriginSubtype {
        if (owner == target) return OriginSubtype.YES
        val node = definition(owner)
        return if (node == null || !visiting.add(owner)) OriginSubtype.UNKNOWN else {
            val ancestors = (node.interfaces + listOfNotNull(node.superName)).map { subtype(it, target, visiting) }
            visiting.remove(owner)
            when {
                OriginSubtype.YES in ancestors -> OriginSubtype.YES
                OriginSubtype.UNKNOWN in ancestors -> OriginSubtype.UNKNOWN
                else -> OriginSubtype.NO
            }
        }
    }

    private fun definition(owner: String): ClassNode? {
        definitions[owner]?.let { return it }
        return if (owner in absent) null else {
            val bytes = try {
                platformBytes(owner) ?: dependencies.firstNotNullOfOrNull { dependencyBytes(it, owner) }
            } catch (_: UnverifiedOriginHierarchy) {
                null // A dependency cannot establish a unique ancestry under the recorded compilation environment.
            }
            val node = bytes?.let { content -> ClassNode().also {
                ClassReader(content).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            } }
            if (node == null || node.name != owner) absent += owner else definitions[owner] = node
            definitions[owner]
        }
    }

    private fun platformBytes(owner: String): ByteArray? =
        ClassLoader.getPlatformClassLoader().getResourceAsStream("$owner.class")?.use { it.readAllBytes() }

    private fun dependencyBytes(path: Path, owner: String): ByteArray? = try {
        when {
            Files.isDirectory(path) -> path.resolve("$owner.class").takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            path.toString().endsWith(".jar") -> archiveBytes(path, owner)
            else -> null
        }
    } catch (_: IOException) {
        throw UnverifiedOriginHierarchy() // Missing dependencies cannot prove nonserializability.
    }

    private fun archiveBytes(path: Path, owner: String): ByteArray? = ZipFile(path.toFile()).use { archive ->
        val versioned = archive.entries().asSequence().any {
            it.name.startsWith("META-INF/versions/") && it.name.endsWith("/$owner.class")
        }
        if (versioned) throw UnverifiedOriginHierarchy()
        archive.getEntry("$owner.class")?.let { entry ->
            archive.getInputStream(entry).use { it.readAllBytes() }
        }
    }

}

private class UnverifiedOriginHierarchy : IllegalStateException()
