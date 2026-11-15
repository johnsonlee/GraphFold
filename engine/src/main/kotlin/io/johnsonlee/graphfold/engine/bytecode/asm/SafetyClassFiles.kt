package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** Packaged application bytes and declared dependencies only; never host classpath fallback. */
internal class SafetyClassFiles(artifact: Path, private val dependencies: List<Path>) : AutoCloseable {
    private val archives = mutableMapOf<Path, ZipFile>()
    private val classes = mutableMapOf<String, ClassNode?>()
    val applications: Map<String, ClassNode> = readApplication(artifact)

    fun find(owner: String): ClassNode? = if (owner in applications) applications.getValue(owner) else classes.getOrPut(owner) {
        dependencies.firstNotNullOfOrNull { path -> bytes(path, "$owner.class")?.let(::decode) }
    }

    fun method(owner: String, name: String, descriptor: String, seen: Set<String> = emptySet()): Pair<ClassNode, MethodNode>? {
        val type = if (owner in seen) null else find(owner)
        if (type == null) return null
        val found = type.methods.singleOrNull { it.name == name && it.desc == descriptor }
        return if (found != null) type to found else type.superName?.let { method(it, name, descriptor, seen + owner) }
    }

    fun supportsContract(owner: String): Boolean = when {
        owner in applications -> false
        owner.startsWith("java/") -> find(owner) == null
        owner == "kotlin/jvm/internal/Intrinsics" ->
            EngineRuntimeClasses.matches(owner, dependencies.mapNotNull { bytes(it, "$owner.class") })
        else -> false
    }

    private fun readApplication(artifact: Path): Map<String, ClassNode> {
        val bodies = if (Files.isDirectory(artifact)) Files.walk(artifact).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.map { Files.readAllBytes(it) }.toList()
        } else archive(artifact).entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { archive(artifact).getInputStream(it).use { input -> input.readBytes() } }.toList()
        return bodies.map(::decode).associateBy { it.name }
    }

    private fun bytes(path: Path, name: String): ByteArray? = if (Files.isDirectory(path)) {
        path.resolve(name).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
    } else archiveBytes(path, name)

    private fun archiveBytes(path: Path, name: String): ByteArray? {
        val zip = archive(path)
        val entry = zip.getEntry(name)
        return entry?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
    }

    private fun archive(path: Path) = archives.getOrPut(path) { ZipFile(path.toFile()) }
    private fun decode(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
    override fun close() { archives.values.forEach(ZipFile::close) }
}
