package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/** JVM override dispatch can reach a new method through an inherited API with no application call site. */
internal class InheritedEntries {
    private val application = linkedMapOf<String, ClassNode>()

    fun add(node: ClassNode) { application[node.name] = node }

    fun resolve(dependencies: List<Path>, diagnostics: MutableList<String>): Set<String> {
        diagnostics.addAll(ExternalObjectEscape(application).diagnostics())
        val cache = application.mapValues { it.value as ClassNode? }.toMutableMap()
        fun type(name: String): ClassNode? {
            if (name !in cache) cache[name] = load(name, dependencies)
            return cache[name]
        }
        return application.values.flatMap { node ->
            val signatures = linkedSetOf<String>()
            val pending = ArrayDeque(listOfNotNull(node.superName) + node.interfaces)
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (!visited.add(name)) continue
                val parent = type(name)
                if (parent == null) {
                    diagnostics.add("Unresolved inherited API: ${node.name} -> $name")
                } else {
                    parent.methods.filter { inherited(it.access, parent.name, node.name) }
                        .forEach { signatures.add(it.name + it.desc) }
                    pending.addAll(listOfNotNull(parent.superName) + parent.interfaces)
                }
            }
            node.methods.filter { method ->
                !method.name.startsWith('<') && method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == 0 &&
                    method.name + method.desc in signatures
            }.map { "${node.name.replace('/', '.')}.${it.name}${it.desc}" }
        }.toSet()
    }

    private fun inherited(access: Int, parent: String, child: String): Boolean {
        val visible = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 ||
            parent.substringBeforeLast('/', "") == child.substringBeforeLast('/', "")
        return access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == 0 && visible
    }

    private fun load(name: String, dependencies: List<Path>): ClassNode? {
        val resource = "$name.class"
        val platform = ClassLoader.getPlatformClassLoader().getResourceAsStream(resource)?.use { it.readBytes() }
        val bytes = platform ?: dependencies.firstNotNullOfOrNull { dependency ->
            if (Files.isDirectory(dependency)) {
                dependency.resolve(resource).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            } else {
                ZipFile(dependency.toFile()).use { zip -> zip.getEntry(resource)?.let { zip.getInputStream(it).readBytes() } }
            }
        }
        return bytes?.let { ClassNode().also { node ->
            ClassReader(it).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } }
    }
}
