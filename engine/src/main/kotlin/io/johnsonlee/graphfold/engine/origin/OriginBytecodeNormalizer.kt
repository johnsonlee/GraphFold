package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode

internal data class OriginNormalizationResult(val classes: Path, val owners: Map<String, String>, val diagnostics: List<String>)

/** Remapping is restricted to ASM's typed JVM references. User strings are never interpreted as class names. */
internal fun normalizeOriginClasses(
    classes: Path, destination: Path, owners: Map<String, String>, dependencies: List<Path> = emptyList(),
): OriginNormalizationResult {
    require(Files.isDirectory(classes)) { "Origin normalization requires a compiled class directory" }
    require(classes.toAbsolutePath().normalize() != destination.toAbsolutePath().normalize()) {
        "Origin normalization must write to a separate analysis directory"
    }
    val files = Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).sorted().toList() }
    val nodes = files.filter { it.toString().endsWith(".class") }.map { file ->
        ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
    }
    val diagnostics = mutableListOf<String>()
    val mapped = deriveNestedOriginOwners(nodes, owners, diagnostics)
    diagnostics += originNameObservability(nodes, mapped, dependencies)
    val remapper = OriginRemapper(mapped)
    Files.createDirectories(destination)
    files.filterNot { it.toString().endsWith(".class") }.forEach { file ->
        val target = destination.resolve(classes.relativize(file))
        Files.createDirectories(target.parent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    }
    val emitted = mutableSetOf<String>()
    nodes.forEach { node ->
        stripOriginMetadata(node, owners.keys.map { it.replace('.', '/') }.toSet())
        normalizeOriginMetadata(node, remapper, mapped, diagnostics)
        val writer = ClassWriter(0)
        node.accept(ClassRemapper(writer, remapper))
        val name = remapper.map(node.name)
        if (!emitted.add(name)) {
            diagnostics += "Origin normalization produces duplicate JVM owner: $name"
        } else {
            val target = destination.resolve("$name.class")
            Files.createDirectories(target.parent)
            Files.write(target, writer.toByteArray())
        }
    }
    return OriginNormalizationResult(destination, mapped, diagnostics)
}

private fun stripOriginMetadata(node: ClassNode, anonymous: Set<String>) {
    node.visibleAnnotations?.removeAll { it.desc == ORIGIN_MARKER }
    node.invisibleAnnotations?.removeAll { it.desc == ORIGIN_MARKER }
    // Index tables do not execute code. Keep the class's own entry, flags and enclosing method intact.
    node.innerClasses.removeAll { it.name != node.name && it.name in anonymous }
    node.nestMembers?.removeAll { it in anonymous }
}

private fun deriveNestedOriginOwners(
    nodes: List<ClassNode>, owners: Map<String, String>, diagnostics: MutableList<String>,
): Map<String, String> {
    val mapped = owners.mapKeys { it.key.replace('.', '/') }.mapValues { it.value.replace('.', '/') }.toMutableMap()
    var changed: Boolean
    do {
        changed = false
        nodes.filter { it.name !in mapped }.forEach { node ->
            val target = nestedOriginOwner(node, mapped, diagnostics)
            if (target != null) {
                mapped[node.name] = target
                changed = true
            }
        }
    } while (changed)
    return mapped.mapKeys { it.key.replace('/', '.') }.mapValues { it.value.replace('/', '.') }
}

private fun nestedOriginOwner(node: ClassNode, mapped: Map<String, String>, diagnostics: MutableList<String>): String? {
    val parent = node.innerClasses.singleOrNull { it.name == node.name }?.outerName ?: node.outerClass
    val targetParent = mapped[parent] ?: return null
    return if (node.name.startsWith("$parent$")) {
        targetParent + node.name.removePrefix(requireNotNull(parent))
    } else {
        diagnostics += "Nested emitted owner has an unsupported binary layout: ${node.name} inside $parent"
        null
    }
}

private fun normalizeOriginMetadata(
    node: ClassNode, remapper: Remapper, owners: Map<String, String>, diagnostics: MutableList<String>,
) {
    (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
        .filter { it.desc == "Lkotlin/Metadata;" }.forEach { annotation ->
            try {
                remapKotlinOriginMetadata(annotation, remapper, owners)
            } catch (failure: IllegalArgumentException) {
                diagnostics += "Unsupported Kotlin origin metadata in ${node.name}: ${failure.message}"
            } catch (failure: IllegalStateException) {
                diagnostics += "Unsupported Kotlin origin metadata in ${node.name}: ${failure.message}"
            }
        }
}

internal class OriginRemapper(owners: Map<String, String>) : Remapper(org.objectweb.asm.Opcodes.ASM9) {
    private val internalNames = owners.mapKeys { it.key.replace('.', '/') }.mapValues { it.value.replace('.', '/') }
    override fun map(internalName: String): String = internalNames[internalName] ?: internalName
}
