package io.johnsonlee.graphfold.engine.inputs.archive

import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes

/**
 * Content evidence for the production outputs explicitly exported by a build adapter.
 * Keys identify logical output positions and relative paths, never checkout locations or timestamps.
 * Equality proves equality only within the adapter's exported production scope.
 *
 * @property entries Complete application class, resource, input dependency, and packaged artifact inventory.
 * @property nonClassEntries The same inventory excluding application classes already represented by [entries].
 * Packaged or resource-directory classes are excluded only when their JVM location and content match an application class.
 * Dependency classes receive the same content and location check; unknown or transformed classes,
 * resources, manifests, dependency order, and opaque dependency files remain included.
 * @property resourceEntries Parsed properties resources from declared production resource outputs.
 * @property resourceIndependentEntries Non-class output identity with verified copies of [resourceEntries] replaced by path markers.
 * @property resourceConsumersKnown Whether all input class layouts permit resource-consumer analysis.
 * This is only a prerequisite: every [resourceDependencies] input still requires complete graph-based resource evidence.
 * @property resourceDependencies Input class directories and archives whose external methods must participate in
 * resource reachability proof. Paths locate build inputs and are not part of the portable content identities above.
 * @property classPlacements Each merged application class's relative path mapped to its matching resource,
 * input dependency, and packaged artifact locations. Empty sets identify classes absent from those outputs.
 * Comparing placements for classes shared by both revisions distinguishes packaging changes from bytecode changes.
 */
typealias ProductionInventory = io.johnsonlee.graphfold.engine.inputs.ContentInventory

internal fun captureProductionInventory(
    classes: Path,
    resourceDirectories: List<Path>,
    inputDependencies: List<Path>,
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
            consumersKnown = consumersKnown && known && resourceManifestKnown(root, name)
        }
    }
    val inputs = captureInputDependencies(inputDependencies, application, resources, placements, entries, nonClasses, independent)
    consumersKnown = consumersKnown && inputs.consumersKnown
    artifacts.forEachIndexed { index, artifact ->
        checkInput(Files.isRegularFile(artifact), "Missing production artifact: $artifact")
        val contents = archiveContents(artifact) { known -> consumersKnown = consumersKnown && known }
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
        resources.values, independent.toMap(), consumersKnown, inputs.dependencies)
}

private fun directoryContents(root: Path): Map<String, String> {
    if (!Files.exists(root)) return emptyMap()
    checkInput(Files.isDirectory(root), "Production output is not a directory: $root")
    return Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { files ->
        files.filter { Files.isRegularFile(it) }.sorted().toList().associate { file ->
            root.relativize(file).joinToString("/") to Files.newInputStream(file).use(::contentHash)
        }
    }
}

private fun applicationClass(name: String, hash: String, application: Map<String, String>, packaged: Boolean): String? {
    val className = when {
        application[name] == hash -> name
        packaged -> resourcePath(name)
        else -> name
    }
    return className.takeIf { name.endsWith(".class") && application[it] == hash }
}

private fun archiveContents(
    archive: Path, manifestScope: ((Boolean) -> Unit)? = null,
): Map<String, String> = ZipFile(archive.toFile()).use { zip ->
    val names = mutableSetOf<String>()
    val contents = sortedMapOf<String, String>()
    zip.entries().asSequence().forEach { entry ->
        checkInput(names.add(entry.name), "Duplicate production archive entry ${entry.name}: $archive")
        if (!entry.isDirectory) contents[entry.name] = archiveEntryHash(zip, entry, manifestScope)
    }
    contents
}

private fun archiveEntryHash(zip: ZipFile, entry: ZipEntry, manifestScope: ((Boolean) -> Unit)?): String =
    zip.getInputStream(entry).use { input ->
        if (manifestScope != null && isManifest(entry.name)) {
            val bytes = input.readBytes()
            manifestScope(runCatching { manifestResourceScope(bytes.inputStream()) }.getOrDefault(false))
            bytes.inputStream().use(::contentHash)
        } else contentHash(input)
    }

private fun resourceManifestKnown(root: Path, name: String): Boolean = !isManifest(name) ||
    runCatching { manifestResourceScope(Files.newInputStream(root.resolve(name))) }.getOrDefault(false)

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

/** Records selected static inputs without confusing graph eligibility with completed resource proof. */
private fun captureInputDependencies(
    dependencies: List<Path>, application: Map<String, String>, resources: PropertyResources,
    placements: Map<String, MutableSet<String>>, entries: MutableMap<String, String>, nonClasses: MutableMap<String, String>,
    independent: MutableMap<String, String>,
): InputResourceInventory {
    var consumersKnown = true
    val resourceDependencies = mutableListOf<Path>()
    dependencies.forEachIndexed { index, dependency ->
        checkInput(Files.exists(dependency), "Missing input dependency: $dependency")
        val packaged = !Files.isDirectory(dependency)
        val contents = when {
            !packaged -> directoryContents(dependency)
            dependency.fileName.toString().substringAfterLast('.') in setOf("jar", "zip", "war") -> archiveContents(dependency)
            else -> null
        }
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
            when (dependencyResourceScope(dependency, contents.keys, nonClass.keys, application.keys)) {
                ResourceDependencyScope.ANALYZABLE -> resourceDependencies.add(dependency)
                ResourceDependencyScope.UNKNOWN -> consumersKnown = false
                ResourceDependencyScope.NONE -> Unit
            }
        }
    }
    return InputResourceInventory(consumersKnown, resourceDependencies.toList())
}

private data class InputResourceInventory(val consumersKnown: Boolean, val dependencies: List<Path>)

private fun resourceBundleConsumersKnown(classes: Path): Boolean {
    if (!Files.exists(classes)) return true
    return Files.walk(classes, FileVisitOption.FOLLOW_LINKS).use { files ->
        files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.allMatch { file ->
            runCatching {
                val reader = ClassReader(Files.readAllBytes(file))
                ordinaryResourceConsumer(reader) && applicationResourceAnnotationsKnown(reader)
            }.getOrDefault(false)
        }
    }
}

private enum class ResourceDependencyScope { NONE, ANALYZABLE, UNKNOWN }

private fun dependencyResourceScope(
    dependency: Path, entries: Set<String>, external: Set<String>, application: Set<String>,
): ResourceDependencyScope {
    val classes = external.filter { it.endsWith(".class") }
    val manifests = entries.filter(::isManifest)
    val nestedArchive = entries.any { it.substringAfterLast('.') in setOf("jar", "zip", "war") }
    val mixedApplication = classes.isNotEmpty() && entries.any { it.endsWith(".class") && it !in external }
    val valid = !nestedArchive && !mixedApplication && runCatching {
        if (Files.isDirectory(dependency)) {
            manifests.all { manifestResourceScope(Files.newInputStream(dependency.resolve(it))) } &&
                classes.all { name -> dependencyClassKnown(name, Files.readAllBytes(dependency.resolve(name)), application) }
        } else {
            ZipFile(dependency.toFile()).use { archive ->
                manifests.all { manifestResourceScope(archive.getInputStream(archive.getEntry(it))) } &&
                    classes.all { name ->
                        dependencyClassKnown(name, archive.getInputStream(archive.getEntry(name)).use { it.readBytes() }, application)
                    }
            }
        }
    }.getOrDefault(false)
    return when {
        !valid -> ResourceDependencyScope.UNKNOWN
        classes.any { !moduleMetadata(it) } -> ResourceDependencyScope.ANALYZABLE
        else -> ResourceDependencyScope.NONE
    }
}

private fun manifestResourceScope(input: InputStream): Boolean = input.use {
    Manifest(it).mainAttributes.getValue(Attributes.Name.CLASS_PATH).isNullOrBlank()
}

private fun isManifest(name: String): Boolean = name.equals("META-INF/MANIFEST.MF", ignoreCase = true)

private fun dependencyClassKnown(name: String, bytes: ByteArray, application: Set<String>): Boolean {
    val reader = ClassReader(bytes)
    val consumerKnown = ordinaryResourceConsumer(reader)
    val module = moduleMetadata(name) && reader.className == "module-info" && reader.access and Opcodes.ACC_MODULE != 0
    val ordinary = !moduleMetadata(name) && name == "${reader.className}.class" && name !in application
    return (module || ordinary) && consumerKnown
}

private fun moduleMetadata(name: String): Boolean = name == "module-info.class" ||
    Regex("META-INF/versions/[0-9]+/module-info\\.class").matches(name)

private fun ordinaryResourceConsumer(reader: ClassReader): Boolean = moduleResourceScope(reader) && reader.superName !in setOf(
    "java/util/ResourceBundle", "java/util/ListResourceBundle", "java/util/PropertyResourceBundle", "java/util/ResourceBundle\$Control",
) && reader.interfaces.none { it in setOf("java/util/spi/ResourceBundleProvider", "java/util/spi/ResourceBundleControlProvider") }

/** A module service factory need not implement its service interface and can be invoked by platform discovery. */
private fun moduleResourceScope(reader: ClassReader): Boolean {
    var hasProviders = false
    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitModule(name: String, access: Int, version: String?): ModuleVisitor = object : ModuleVisitor(Opcodes.ASM9) {
            override fun visitProvide(service: String, vararg providers: String) {
                hasProviders = true
            }
        }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return !hasProviders
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
