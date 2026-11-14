package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.JdkRuntimeClasses
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * Independent dispatch proof: a final class or non-overridable method fixes the target, including
 * JDK/library declarations. A declared application method also has no overriding receiver when
 * its owner has no subtype in the complete supplied classpath. This does not model
 * dynamically generated/loaded classes, prove method effects, or authorize fold-target selection.
 * Framework scopes that can create runtime subclasses must disable the application-leaf inference.
 * Duplicate or unsupported inventories cannot establish this proof. Fixed declarations require
 * their own hierarchy; application-leaf proofs additionally require every supplied hierarchy.
 */
internal class LeafApplicationDispatch(
    artifact: Path, private val applicationOwners: Set<String>, private val allowApplicationLeaf: Boolean = true,
) {
    private val types = linkedMapOf<String, ClassNode>()
    private val runtimeTypes = hashMapOf<String, ClassNode?>()
    private var complete = true
    private var completeHierarchy = true
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
            completeHierarchy = declarations.all { hierarchy(it.name, checked, hashSetOf()) }
        } catch (_: IOException) {
            complete = false
        } catch (_: IllegalArgumentException) {
            complete = false
        } catch (_: IndexOutOfBoundsException) {
            complete = false
        }
    }

    /** Proves the exact target declaration; callers must supply independently established invocation evidence. */
    fun proves(identity: String): Boolean {
        val owner = identity.substringBefore('(').substringBeforeLast('.')
        val type = declaration(owner.replace('.', '/'))
        val method = type?.methods?.singleOrNull { it.name + it.desc == identity.removePrefix("$owner.") }
        if (!complete || type == null || method == null) return false
        // This proves the invocation target only. External effects and missing/native bodies keep
        // their independent guards; an overridable library method cannot borrow the application-leaf rule.
        val fixedMethod = method.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) != 0
        val concreteOwner = type.access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) == 0
        val fixedDeclaration = fixedMethod || concreteOwner && type.access and Opcodes.ACC_FINAL != 0
        val fixedTarget = if (fixedDeclaration) {
            hierarchy(type.name, hashSetOf(), hashSetOf())
        } else allowApplicationLeaf && concreteOwner && completeHierarchy && owner in applicationOwners && type.name !in parents
        return method.access and Opcodes.ACC_ABSTRACT == 0 && fixedTarget
    }

    private fun declaration(name: String): ClassNode? = types[name] ?: runtimeTypes.computeIfAbsent(name) {
        JdkRuntimeClasses.bytes(it)?.let { bytes ->
            ClassNode().also { node ->
                ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
        }
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
            if (versioned) complete = false
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
            val node = declaration(name)
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
