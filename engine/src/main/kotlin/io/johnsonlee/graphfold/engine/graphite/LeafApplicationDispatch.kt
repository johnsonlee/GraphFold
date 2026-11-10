package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.JdkRuntimeClasses
import io.johnsonlee.graphite.core.CallSiteNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * Independent closed-input dispatch proof: a declared application method has no overriding
 * receiver when its owner has no subtype in the complete supplied classpath. This does not model
 * dynamically generated/loaded classes, prove method effects, or authorize fold-target selection.
 * Missing, duplicate, cyclic or unsupported class inventories cannot establish this proof.
 */
internal class LeafApplicationDispatch(artifact: Path, private val applicationOwners: Set<String>) {
    private val types = linkedMapOf<String, ClassNode>()
    private var complete = true
    private val parents = hashSetOf<String>()

    init {
        try {
            read(artifact)
            val declarations = types.values.toList()
            declarations.forEach { node ->
                parents.addAll(node.interfaces)
                node.superName?.let(parents::add)
            }
            val checked = hashSetOf<String>()
            complete = complete && declarations.all { hierarchy(it.name, checked, hashSetOf()) }
        } catch (_: IOException) {
            complete = false
        } catch (_: IllegalArgumentException) {
            complete = false
        } catch (_: IndexOutOfBoundsException) {
            complete = false
        }
    }

    /** Requires an actual surviving direct call, not a speculative derived target or an absent body. */
    fun proves(site: CallSiteNode, methods: Map<String, MethodSnapshot>): Boolean {
        val owner = site.callee.declaringClass.className
        val type = types[owner.replace('.', '/')]
        val declaration = type?.methods?.singleOrNull { it.name + it.desc == site.callee.name + site.callee.descriptor }
        return complete && owner in applicationOwners && type != null && type.name !in parents &&
            type.access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) == 0 &&
            declaration != null && declaration.access and Opcodes.ACC_ABSTRACT == 0 &&
            (site.ordinal ?: -1) >= 0 && site.callee.identity in methods[site.caller.identity]?.calls.orEmpty()
    }

    private fun read(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { paths -> paths.filter(Files::isRegularFile).forEach(::readFile) }
        } else readFile(path)
    }

    private fun readFile(path: Path) {
        when {
            path.toString().endsWith(".class") -> add(Files.readAllBytes(path))
            path.toString().endsWith(".jar") -> archive(path)
            ARCHIVES.any { path.toString().endsWith(it, ignoreCase = true) } -> complete = false
        }
    }

    private fun archive(path: Path) {
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            if (entries.map { it.name }.distinct().size != entries.size) complete = false
            val versioned = entries.any { it.name.startsWith("META-INF/versions/") && it.name.endsWith(".class") }
            if (versioned && !metadataOnlyMultiRelease(path, path.fileName.toString())) complete = false
            entries.forEach { entry -> archiveEntry(zip, entry) }
        }
    }

    private fun add(bytes: ByteArray) {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (node.name != "module-info") {
            // The JVM rejects parentless ordinary classes and resolves platform classes before supplied definitions.
            if (node.superName == null || JdkRuntimeClasses.contains(node.name)) complete = false
            if (types.put(node.name, node) != null) complete = false
        }
    }

    private fun archiveEntry(zip: ZipFile, entry: ZipEntry) {
        when {
            ARCHIVES.any { entry.name.endsWith(it, ignoreCase = true) } -> complete = false
            entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/versions/") ->
                zip.getInputStream(entry).use { add(it.readBytes()) }
        }
    }

    private fun hierarchy(name: String, checked: MutableSet<String>, visiting: MutableSet<String>): Boolean = when {
        name in checked -> true
        !visiting.add(name) -> false
        else -> {
            val node = types[name] ?: JdkRuntimeClasses.bytes(name)?.let { bytes ->
                ClassNode().also {
                    ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
            val result = node != null &&
                (node.interfaces + listOfNotNull(node.superName)).all { hierarchy(it, checked, visiting) }
            visiting.remove(name)
            if (result) checked.add(name)
            result
        }
    }

    private companion object {
        val ARCHIVES = listOf(".jar", ".zip", ".war", ".ear")
    }
}
