package io.johnsonlee.graphfold.engine.inputs.archive

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * Normalizes explicitly selected static classpaths without selecting bytecode by the analyzer JVM.
 * All bytecode consumers must receive these same paths; production inventories retain the original archives.
 * Multi-release alternatives are explicit variants. Validated module descriptors remain metadata, while service
 * resources remain in the selected classpath. Different class definitions are ambiguous; independent scopes are never combined.
 */
internal fun normalizeDependencyInputs(
    dependencies: List<Path>, output: Path, variantThreshold: Int = 0,
    applicationClasses: Path? = null, cache: DependencyInputCache? = null,
): List<Path> {
    val selection = StaticClassSelection(applicationClasses)
    return dependencies.mapIndexed { index, dependency ->
        checkInput(Files.exists(dependency), "Missing input dependency: $dependency")
        val target = output.resolve("dependency-$index.jar")
        val produce = {
            if (Files.isDirectory(dependency)) materializeDirectory(dependency, target, selection)
            else materializeArchive(dependency, target, selection, variantThreshold)
        }
        if (cache == null) produce()
        else cache.materialize(dependency, variantThreshold, applicationClasses, selection.selected, produce)
    }
}

private fun materializeArchive(
    dependency: Path, target: Path, selection: StaticClassSelection, variantThreshold: Int,
): Path = JarFile(dependency.toFile()).use { archive ->
    val physical = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
    checkInput(physical.map { it.name }.distinct().size == physical.size, "Duplicate input archive entry: $dependency")
    val selectedEntries = staticArchiveEntries(archive, variantThreshold)
    validateArchiveModules(archive, selectedEntries.values.toList(), dependency)
    val entries = selectedEntries.keys.map(::JarEntry)
    fun contents(entry: JarEntry): ByteArray = archive.getInputStream(selectedEntries.getValue(entry.name)).use { it.readBytes() }
    val retained = entries.filter { entry ->
        !opaquePackagedResource(entry.name) { contents(entry) } &&
            selection.retain(entry.name) { contents(entry) }
    }
    retained.filter { it.name.endsWith(".class") }.forEach { entry ->
        validateStaticClass(entry.name, contents(entry), dependency)
    }
    if (!requiresArchiveProjection(archive, physical, entries.size, retained.size)) return@use dependency
    validateArchiveProjection(archive, dependency)
    writeInputArchive(target) { zip ->
        retained.forEach { entry ->
            val bytes = contents(entry)
            writeEntry(zip, entry.name, if (entry.name == JarFile.MANIFEST_NAME) projectedManifest(bytes) else bytes)
        }
    }
    target
}

private fun requiresArchiveProjection(archive: JarFile, physical: List<JarEntry>, entries: Int, retained: Int): Boolean =
    retained != entries || physical.any { it.name.startsWith(VERSIONS) } ||
        archive.manifest?.mainAttributes?.getValue(Attributes.Name.MULTI_RELEASE).equals("true", ignoreCase = true)

private fun validateArchiveModules(archive: JarFile, entries: List<JarEntry>, dependency: Path) {
    entries.filter { it.name == MODULE_DESCRIPTOR || VERSIONED_MODULE.matches(it.name) }.forEach { entry ->
        archive.getInputStream(entry).use { validateModuleDescriptor(it.readBytes(), dependency) }
    }
}

private fun validateArchiveProjection(archive: JarFile, dependency: Path) {
    checkInput(archive.manifest?.let { it.mainAttributes.getValue(Attributes.Name.CLASS_PATH) }.isNullOrBlank(),
        "Implicit manifest Class-Path cannot be projected: $dependency")
}

private fun materializeDirectory(
    dependency: Path, target: Path, selection: StaticClassSelection,
): Path {
    val files = Files.walk(dependency).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }
    val entries = files.associateBy { dependency.relativize(it).joinToString("/") }
    entries.filterKeys { it == MODULE_DESCRIPTOR }.values.forEach { file ->
        validateModuleDescriptor(Files.readAllBytes(file), dependency)
    }
    val retained = entries.filter { (name, file) ->
        !name.startsWith(VERSIONS) && !opaquePackagedResource(name) { Files.readAllBytes(file) } &&
            selection.retain(name) { Files.readAllBytes(file) }
    }
    retained.filterKeys { it.endsWith(".class") }.forEach { (name, file) ->
        validateStaticClass(name, Files.readAllBytes(file), dependency)
    }
    if (retained.size == entries.size) return dependency
    writeInputArchive(target) { zip ->
        retained.forEach { (name, file) ->
            val bytes = Files.readAllBytes(file)
            writeEntry(zip, name, if (name == JarFile.MANIFEST_NAME) projectedManifest(bytes) else bytes)
        }
    }
    return target
}

/** A static scope may contain identical copies, but never two different definitions of one class. */
private class StaticClassSelection(applicationClasses: Path?) {
    private val application = applicationClasses?.toAbsolutePath()?.normalize()
    val selected = mutableMapOf<String, String>()

    fun retain(name: String, bytes: () -> ByteArray): Boolean {
        if (name == MODULE_DESCRIPTOR || !name.endsWith(".class")) return name != MODULE_DESCRIPTOR
        val contents = bytes()
        val identity = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it) }
        val previous = selected.putIfAbsent(name, identity)
        checkInput(previous == null || previous == identity, "Ambiguous static class definition: $name")
        val file = application?.let { root ->
            root.resolve(name).normalize().takeIf { it.startsWith(root) && Files.isRegularFile(it) }
        }
        if (file != null) {
            checkInput(Files.readAllBytes(file).contentEquals(contents), "Ambiguous application/dependency class definition: $name")
        }
        return previous == null && file == null
    }
}

private fun validateStaticClass(name: String, bytes: ByteArray, dependency: Path) {
    val reader = staticClassReader(bytes, dependency)
    checkInput(reader.className + ".class" == name, "Static class identity differs from archive path: $dependency!/$name")

}

private fun validateModuleDescriptor(bytes: ByteArray, dependency: Path) {
    val node = ClassNode()
    staticClassReader(bytes, dependency).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    checkInput(node.name == "module-info" && node.access == Opcodes.ACC_MODULE && node.version >= Opcodes.V9 &&
        node.module != null && node.methods.isEmpty() &&
        node.fields.isEmpty() && node.superName == null && node.interfaces.isEmpty(),
        "Invalid input module descriptor: $dependency")
}

/** ASM version failures are unsupported build inputs, not internal analysis failures. */
private fun staticClassReader(bytes: ByteArray, dependency: Path): ClassReader = try {
    ClassReader(bytes)
} catch (failure: IllegalArgumentException) {
    throw InputFailure("Unsupported input bytecode in $dependency: $failure").also { it.initCause(failure) }
}

private fun projectedManifest(bytes: ByteArray): ByteArray {
    val manifest = Manifest(bytes.inputStream())
    manifest.mainAttributes.remove(Attributes.Name.MULTI_RELEASE)
    return ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()
}

private fun writeInputArchive(target: Path, write: (ZipOutputStream) -> Unit) {
    Files.createDirectories(target.parent)
    ZipOutputStream(Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)).use(write)
}

private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
    zip.write(bytes)
    zip.closeEntry()
}

private const val MODULE_DESCRIPTOR = "module-info.class"
private const val VERSIONS = "META-INF/versions/"
private val VERSIONED_MODULE = Regex("""META-INF/versions/[1-9][0-9]*/module-info\.class""")
