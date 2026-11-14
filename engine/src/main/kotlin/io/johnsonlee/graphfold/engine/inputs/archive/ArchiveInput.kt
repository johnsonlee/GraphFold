package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** An explicitly selected archive; compiler outputs provide source evidence, never replacement graph bytes. */
data class ArchiveEntryInput(
    val artifact: Path, val producerOutputs: List<Path> = emptyList(),
    val inventoryOnly: Boolean = false,
    val application: Boolean = producerOutputs.isNotEmpty(),
)

/** One selected archive scope: the primary archive followed by its dependencies, expanded by static bytecode layout. */
data class ArchiveInput(
    val id: String, val classpath: List<ArchiveEntryInput>,
    val archiveMetadata: Map<String, String> = emptyMap(),
    val producerArchives: Map<Path, List<Path>> = emptyMap(),
    val staticDependencies: List<Path> = emptyList(),
    /** Project-owned artifacts identify packaged application libraries independently of optional source mappings. */
    val applicationArchives: Set<Path> = emptySet(),
)

/** Graph classes come from unambiguous definitions in final packaged artifacts. */
internal fun prepareArchiveInput(
    input: ArchiveInput, provenance: InputProject, output: Path, variantThreshold: Int = 0,
): InputProject {
    // Unreadable or malformed archives cannot establish a static graph input; reject them before any expansion.
    input.classpath.forEach { canonicalPackagedArchive(it.artifact) }
    val selected = input.copy(classpath = input.classpath.mapIndexed { index, entry ->
        val selectedPrimary = index == 0 && !entry.inventoryOnly
        entry.copy(application = entry.application || selectedPrimary || input.ownsApplicationArchive(entry.artifact))
    })
    val expanded = springBootArchiveInput(selected, output.resolve("boot"))
        ?: warArchiveInput(selected, output.resolve("war")) ?: selected
    return prepareFlatArchiveInput(expanded, provenance, output, variantThreshold).copy(archiveInput = input)
}

/** Membership is independent of source association; legacy producer records also establish project ownership. */
internal fun ArchiveInput.ownsApplicationArchive(artifact: Path): Boolean =
    (applicationArchives + producerArchives.keys).any { Files.isRegularFile(it) && Files.mismatch(it, artifact) == -1L }

private fun prepareFlatArchiveInput(
    input: ArchiveInput, provenance: InputProject, output: Path, variantThreshold: Int,
): InputProject {
    checkInput(input.classpath.isNotEmpty(), "Input scope ${input.id} has no packaged classpath")
    val original = input.classpath.map { canonicalPackagedArchive(it.artifact) }
    val loaded = original.filterIndexed { index, _ -> !input.classpath[index].inventoryOnly }
    checkInput(expandManifestClasspath(loaded) == loaded,
        "Input scope ${input.id} must export its expanded manifest classpath in lookup order")
    val restored = original.mapIndexed { index, artifact ->
        selectedArchiveInventory(artifact, variantThreshold, output.resolve("inventory/$index.jar"))
    }
    val ordinary = original.mapIndexed { index, path ->
        val archive = output.resolve("archives/$index.jar")
        if (input.classpath[index].inventoryOnly) {
            Files.createDirectories(archive.parent)
            java.util.jar.JarOutputStream(Files.newOutputStream(archive)).use { }
            archive
        } else normalizePackagedArchive(path, archive)
    }
    // Identical definitions may deduplicate, but an application's copy must retain its application role.
    // Different definitions still fail shared normalization regardless of this role-based processing order.
    val classOrder = input.classpath.indices.sortedBy { if (input.classpath[it].application) 0 else 1 }
    val normalized = normalizeDependencyInputs(classOrder.map(ordinary::get), output.resolve("effective"),
        variantThreshold = variantThreshold)
    val byIndex = classOrder.zip(normalized).toMap()
    val effective = ordinary.indices.map(byIndex::getValue)
    val classes = Files.createDirectories(output.resolve("classes"))
    input.classpath.indices.filter { input.classpath[it].application }.forEach { index ->
        copyPackagedClasses(effective[index], classes)
    }
    val dependencies = normalizeDependencyInputs(effective, output.resolve("dependencies"),
        variantThreshold = variantThreshold, applicationClasses = classes)
    val ownedOutputs = input.classpath.flatMap { it.producerOutputs }.map { it.toFile().canonicalFile.toPath() }.toSet()
    val sourceOutputs = provenance.sourceOutputs.entries.groupBy({ it.key.toFile().canonicalFile.toPath() }, { it.value })
        .mapValues { (_, groups) -> groups.flatten().map { it.toFile().canonicalFile.toPath() }
            .filter { it in ownedOutputs }.distinct() }.filterValues { it.isNotEmpty() }
    val resources = restored.mapIndexed { index, artifact ->
        packagedResources(artifact, output.resolve("resources/$index"), original = artifact == original[index])
    }
    val inventory = captureProductionInventory(classes, resources, restored,
        input.classpath.indices.filter { input.classpath[it].application }.map { restored[it] })
    val issues = provenance.provenanceIssues.toMutableList()
    val compilerClasspaths = canonicalCompilerClasspaths(provenance.sourceClasspaths, sourceOutputs.keys, issues)
    return provenance.copy(classes = classes, dependencies = dependencies, sourceRoots = sourceOutputs.keys.filter(Files::isDirectory),
        sourceOutputs = sourceOutputs, analysisScopes = emptyMap(),
        sourceClasspaths = compilerClasspaths, provenanceIssues = issues,
        compilerEvidence = provenance.compilerEvidence.filter { it.classes.toFile().canonicalFile.toPath() in ownedOutputs }
            .map { it.copy(classes = it.classes.toFile().canonicalFile.toPath()) },
        production = inventory.withArchiveMetadata(input).copy(resourceDependencies = dependencies))
}

private fun canonicalCompilerClasspaths(
    classpaths: Map<Path, List<Path>>, roots: Set<Path>, issues: MutableList<String>,
): Map<Path, List<Path>> =
    classpaths.entries.groupBy({ it.key.toFile().canonicalFile.toPath() }, { it.value }).filterKeys { it in roots }
        .mapValues { (source, values) ->
            val contexts = values.map { dependencies -> dependencies.map { it.toFile().canonicalFile.toPath() }.distinct() }.distinct()
            if (contexts.size != 1) issues += "Ambiguous compiler classpaths for source root: $source"
            contexts.singleOrNull().orEmpty()
        }

private fun copyPackagedClasses(archive: Path, output: Path) {
    JarFile(archive.toFile()).use { jar ->
        jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
            val target = output.resolve(entry.name).normalize()
            checkInput(target.startsWith(output) && output.relativize(target).joinToString("/") == entry.name,
                "Unsupported packaged class path: ${entry.name}")
            Files.createDirectories(target.parent)
            jar.getInputStream(entry).use { Files.copy(it, target) }
        }
    }
}

/** Preserve each artifact's effective resource entries separately; conflicting properties remain unproved. */
private fun packagedResources(artifact: Path, output: Path, original: Boolean): Path {
    Files.createDirectories(output)
    // The inventory projection preserves signature evidence, but only original bytes can be authenticated.
    val archive = if (original) JarFile(artifact.toFile()) else ZipFile(artifact.toFile())
    archive.use { jar ->
        jar.stream().use { entries ->
            entries.filter { !it.isDirectory && !it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                .forEach { entry -> copyPackagedResource(jar, entry, output) }
        }
    }
    return output
}

/** Preserve static archive-layout evidence independently of application classes. */
private fun ProductionInventory.withArchiveMetadata(input: ArchiveInput): ProductionInventory {
    val inputs = input.archiveMetadata.mapKeys { (name, _) -> "archive/layout/$name" }
    return copy(entries = entries + inputs, nonClassEntries = nonClassEntries + inputs,
        resourceIndependentEntries = resourceIndependentEntries + inputs)
}

private fun copyPackagedResource(jar: ZipFile, entry: ZipEntry, output: Path) {
    val target = output.resolve(entry.name).normalize()
    checkInput(target.startsWith(output) && target != output && output.relativize(target).joinToString("/") == entry.name,
        "Unsupported packaged resource path: ${entry.name}")
    Files.createDirectories(target.parent)
    jar.getInputStream(entry).use { Files.copy(it, target) }
}
