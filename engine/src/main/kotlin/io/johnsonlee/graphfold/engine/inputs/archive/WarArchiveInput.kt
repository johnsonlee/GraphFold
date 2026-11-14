package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path

/** Static WAR scope consists of packaged application classes/libraries and explicitly provided external types. */
internal fun warArchiveInput(input: ArchiveInput, output: Path): ArchiveProjection? {
    val wars = input.classpath.filter { it.artifact.fileName.toString().endsWith(".war", ignoreCase = true) }
    if (wars.isEmpty()) return null
    checkInput(wars.size == 1, "A WAR input must identify one WAR: ${input.id}")
    val war = wars.single()
    Files.createDirectories(output)
    val restored = war.artifact
    val entries = readWarArchive(restored)
    val classes = entries.filterKeys { it.startsWith(WAR_CLASSES) }.mapKeys { it.key.removePrefix(WAR_CLASSES) }
    val libraries = entries.filterKeys(::warLibrary)
    val envelope = entries.filterKeys { !it.startsWith(WAR_CLASSES) && !warLibrary(it) }
    val webClasses = writeAnalysisArchive(output.resolve("web-classes.jar"), classes)
    val webLibraries = libraries.entries.mapIndexed { index, (name, bytes) ->
        val path = output.resolve("web-lib/$index-${name.substringAfterLast('/')}")
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        ArchiveEntryInput(path, input.producerArchives.entries.filter { (artifact, _) ->
            Files.isRegularFile(artifact) && Files.mismatch(artifact, path) == -1L
        }.flatMap { it.value }.distinct(), application = input.ownsApplicationArchive(path))
    }
    val web = listOf(ArchiveEntryInput(webClasses, war.producerOutputs, application = true)) + webLibraries
    val provided = input.staticDependencies.mapIndexed { index, dependency ->
        val archive = if (Files.isDirectory(dependency)) {
            val files = Files.walk(dependency).use { paths -> paths.filter(Files::isRegularFile).toList() }
                .associate { dependency.relativize(it).joinToString("/") to Files.readAllBytes(it) }
            writeAnalysisArchive(output.resolve("provided/$index.jar"), files)
        } else dependency
        ArchiveEntryInput(archive)
    }
    val ordered = web + provided
    val resources = writeAnalysisArchive(output.resolve("web-resources.jar"), envelope)
    return ArchiveProjection(input.copy(classpath = ordered + ArchiveEntryInput(resources, inventoryOnly = true),
        archiveMetadata = input.archiveMetadata + mapOf("kind" to "war",
            "webResources" to archiveContentIdentity(envelope))), StaticClassOrder.UNAMBIGUOUS)
}

private fun warLibrary(name: String): Boolean = name.startsWith("WEB-INF/lib/") &&
    '/' !in name.removePrefix("WEB-INF/lib/") && name.endsWith(".jar", ignoreCase = true)

private const val WAR_CLASSES = "WEB-INF/classes/"
