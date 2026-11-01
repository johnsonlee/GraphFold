package io.johnsonlee.graphfold.engine.build

import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Content evidence for the production outputs explicitly exported by a build adapter.
 * Keys identify logical output positions and relative paths, never checkout locations or timestamps.
 * Equality proves equality only within the adapter's exported production scope.
 *
 * @property entries Complete application class, resource, runtime dependency, and packaged artifact inventory.
 * @property nonClassEntries The same inventory excluding application classes already represented by [entries].
 * Packaged or resource-directory classes are excluded only when their JVM location and content match an application class.
 * Dependency classes receive the same content and location check; unknown or transformed classes,
 * resources, manifests, dependency order, and opaque dependency files remain included.
 * @property resourceEntries Parsed properties resources from declared production resource outputs.
 * @property resourceIndependentEntries Non-class output identity with verified copies of [resourceEntries] replaced by path markers.
 * @property resourceConsumersKnown Whether runtime dependencies and packages contain only analyzed application classes.
 * Unknown external code may discover resources without an application loader call.
 * @property classPlacements Each merged application class's relative path mapped to its matching resource,
 * runtime dependency, and packaged artifact locations. Empty sets identify classes absent from those outputs.
 * Comparing placements for classes shared by both revisions distinguishes packaging changes from bytecode changes.
 */
data class ProductionInventory(
    val entries: Map<String, String>,
    val nonClassEntries: Map<String, String>,
    val classPlacements: Map<String, Set<String>> = emptyMap(),
    val resourceEntries: Map<String, Map<String, String>> = emptyMap(),
    val resourceIndependentEntries: Map<String, String> = nonClassEntries,
    val resourceConsumersKnown: Boolean = false,
)

internal fun captureProductionInventory(
    classes: Path,
    resourceDirectories: List<Path>,
    runtimeDependencies: List<Path>,
    artifacts: List<Path>,
): ProductionInventory {
    val application = directoryContents(classes).filterKeys { it.endsWith(".class") }
    val resources = PropertyResources(resourceDirectories)
    var consumersKnown = resources.values.isEmpty() || resourceBundleConsumersKnown(classes)
    val independent = linkedMapOf<String, String>()
    val placements = application.keys.associateWith { linkedSetOf<String>() }
    val entries = linkedMapOf<String, String>()
    val nonClasses = linkedMapOf<String, String>()
    application.forEach { (name, hash) -> entries["class/$name"] = hash }
    resourceDirectories.forEachIndexed { index, root ->
        directoryContents(root).forEach { (name, hash) ->
            val key = "resource/$index/$name"
            entries[key] = hash
            val className = applicationClass(name, hash, application, packaged = false)
            val known = recordEntry(key, name, hash, resources, className?.let(placements::getValue), nonClasses, independent)
            consumersKnown = consumersKnown && known
        }
    }
    runtimeDependencies.forEachIndexed { index, dependency ->
        checkBuild(Files.exists(dependency), "Missing runtime dependency: $dependency")
        val packaged = !Files.isDirectory(dependency)
        val contents = dependencyContents(dependency)
        if (contents == null) {
            val hash = Files.newInputStream(dependency).use(::contentHash)
            entries["dependency/$index"] = "file:$hash"
            nonClasses["dependency/$index"] = "file:$hash"
            independent["dependency/$index"] = "file:$hash"
            consumersKnown = false
        } else {
            val nonClass = contents.filterNot { (name, hash) ->
                val className = applicationClass(name, hash, application, packaged)
                if (className != null) placements.getValue(className).add("dependency/$index/$name")
                className != null
            }
            val type = if (packaged) "archive" else "directory"
            entries["dependency/$index"] = "$type:${contentIdentity(contents)}"
            nonClasses["dependency/$index"] = "$type:${contentIdentity(nonClass)}"
            val normalized = nonClass.mapValues { (name, hash) -> resources.identity(name, hash) }
            independent["dependency/$index"] = "$type:${contentIdentity(normalized)}"
            if (nonClass.keys.any { it.endsWith(".class") }) consumersKnown = false
        }
    }
    artifacts.forEachIndexed { index, artifact ->
        checkBuild(Files.isRegularFile(artifact), "Missing production artifact: $artifact")
        val contents = archiveContents(artifact)
        // Retain the presence and order of archives even when an archive has no file entries.
        entries["artifact/$index"] = "archive"
        nonClasses["artifact/$index"] = "archive"
        independent["artifact/$index"] = "archive"
        contents.forEach { (name, hash) ->
            val key = "artifact/$index/$name"
            entries[key] = hash
            val className = applicationClass(name, hash, application, packaged = true)
            val known = recordEntry(key, name, hash, resources, className?.let(placements::getValue), nonClasses, independent)
            consumersKnown = consumersKnown && known
        }
    }
    return ProductionInventory(entries.toMap(), nonClasses.toMap(), placements.mapValues { it.value.toSet() },
        resources.values, independent.toMap(), consumersKnown)
}

private fun directoryContents(root: Path): Map<String, String> {
    if (!Files.exists(root)) return emptyMap()
    checkBuild(Files.isDirectory(root), "Production output is not a directory: $root")
    return Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { files ->
        files.filter { Files.isRegularFile(it) }.sorted().toList().associate { file ->
            root.relativize(file).joinToString("/") to Files.newInputStream(file).use(::contentHash)
        }
    }
}

private fun applicationClass(name: String, hash: String, application: Map<String, String>, packaged: Boolean): String? {
    val className = if (packaged) resourcePath(name) else name
    return className.takeIf { name.endsWith(".class") && application[it] == hash }
}

private fun archiveContents(archive: Path): Map<String, String> = ZipFile(archive.toFile()).use { zip ->
    val names = mutableSetOf<String>()
    val contents = sortedMapOf<String, String>()
    zip.entries().asSequence().forEach { entry ->
        checkBuild(names.add(entry.name), "Duplicate production archive entry ${entry.name}: $archive")
        if (!entry.isDirectory) contents[entry.name] = zip.getInputStream(entry).use(::contentHash)
    }
    contents
}

private fun contentIdentity(contents: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    contents.toSortedMap().forEach { (name, hash) ->
        digest.update("${name.toByteArray(Charsets.UTF_8).size}:$name:$hash\n".toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun contentHash(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun dependencyContents(dependency: Path): Map<String, String>? = when {
    Files.isDirectory(dependency) -> directoryContents(dependency)
    dependency.fileName.toString().substringAfterLast('.') in setOf("jar", "zip", "war") -> archiveContents(dependency)
    else -> null
}

private fun resourceBundleConsumersKnown(classes: Path): Boolean {
    if (!Files.exists(classes)) return true
    val customBundleParents = setOf("java/util/ResourceBundle", "java/util/ListResourceBundle", "java/util/PropertyResourceBundle",
        "java/util/ResourceBundle\$Control")
    return Files.walk(classes, FileVisitOption.FOLLOW_LINKS).use { files ->
        files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.allMatch { file ->
            runCatching { org.objectweb.asm.ClassReader(Files.readAllBytes(file)).superName !in customBundleParents }.getOrDefault(false)
        }
    }
}

private fun recordEntry(
    key: String, name: String, hash: String, resources: PropertyResources, placements: MutableSet<String>?,
    nonClasses: MutableMap<String, String>, independent: MutableMap<String, String>,
): Boolean {
    if (placements != null) {
        placements.add(key)
        return true
    }
    nonClasses[key] = hash
    independent[key] = resources.identity(name, hash)
    return !name.endsWith(".class")
}

private fun resourcePath(name: String): String = when {
    name.startsWith("BOOT-INF/classes/") -> name.removePrefix("BOOT-INF/classes/")
    name.startsWith("WEB-INF/classes/") -> name.removePrefix("WEB-INF/classes/")
    else -> name
}
