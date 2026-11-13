package io.johnsonlee.graphfold.engine.inputs


import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.git.GitWorkspaceManager
import io.johnsonlee.graphfold.engine.inputs.archive.ArchiveEntryInput
import io.johnsonlee.graphfold.engine.inputs.archive.ArchiveInput
import io.johnsonlee.graphfold.engine.inputs.archive.CompiledRevisionInput
import io.johnsonlee.graphfold.engine.inputs.archive.DependencyInputCache
import io.johnsonlee.graphfold.engine.inputs.archive.InputProject
import io.johnsonlee.graphfold.engine.inputs.archive.captureProductionInventory
import io.johnsonlee.graphfold.engine.inputs.archive.canonicalPackagedArchive
import io.johnsonlee.graphfold.engine.inputs.archive.checkInput
import io.johnsonlee.graphfold.engine.inputs.archive.expandManifestClasspath
import io.johnsonlee.graphfold.engine.inputs.archive.isSpringBootArchive
import io.johnsonlee.graphfold.engine.inputs.archive.selectedArchiveInventory
import io.johnsonlee.graphfold.engine.inputs.archive.prepareRevision
import io.johnsonlee.graphfold.engine.inputs.archive.prepareRevisions
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git

/** Versioned static input bundles. Integrations export their current model; engine only understands bytes and Git identities. */
object StaticInputSnapshots {
    /** Reads validated scope identities without exposing the snapshot storage representation. */
    fun scopeIds(manifest: Path): List<String> = readSnapshot(manifest).scopes.map { it.id }

    /** Copies model inputs before later builds can replace them. [manifest] must not already exist. */
    fun write(repository: Path, manifest: Path, scopes: List<ArchiveScopeInput>): Path {
        validateSnapshotScopes(scopes)
        val target = manifest.toAbsolutePath().normalize()
        checkInput(!Files.exists(target), "Static input snapshot already exists: $target")
        val directory = target.parent
        val (root, sha) = Git.open(repository.toFile()).use { git ->
            val status = git.status().call()
            checkInput(status.isClean,
                "Commit source changes and remove or ignore untracked files before exporting a revision input")
            git.repository.workTree.toPath().toRealPath() to
                requireNotNull(git.repository.resolve("HEAD")) { "Repository has no HEAD" }.name
        }
        Files.createDirectories(directory)
        val assetsDirectory = directory.resolve("${target.fileName}.assets")
        checkInput(!Files.exists(assetsDirectory), "Snapshot asset directory already exists: $assetsDirectory")
        val assets = SnapshotAssets(directory, assetsDirectory)
        val stored = scopes.map { scope ->
            val classpath = expandManifestClasspath(listOf(scope.archive) + scope.dependencies)
            classpath.forEach(::canonicalPackagedArchive)
            fun canonical(path: Path): Path = path.toFile().canonicalFile.toPath()
            scope.copy(archive = assets.copy(classpath.first()), dependencies = classpath.drop(1).map(assets::copy),
                sourceRoots = scope.sourceRoots.map(::canonical),
                declaredSources = scope.declaredSources.map { it.copy(root = canonical(it.root)) },
                sourceOutputs = scope.sourceOutputs.mapKeys { canonical(it.key) }.mapValues { (_, paths) -> paths.map(assets::copy) },
                compilerEvidence = scope.compilerEvidence.map {
                    it.copy(classes = assets.copy(it.classes), manifest = assets.copy(it.manifest))
                }, originDirectory = scope.originDirectory?.let(assets::copy),
                sourceClasspaths = scope.sourceClasspaths.mapKeys { canonical(it.key) }.mapValues { (_, paths) -> paths.map(assets::copy) },
                projectArtifacts = scope.projectArtifacts.mapKeys { assets.copy(canonicalPackagedArchive(it.key)) }
                    .mapValues { (_, paths) -> paths.map(assets::copy) })
        }
        snapshotMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),
            SnapshotRecord(sha = sha, repositoryRoot = root, storedDirectory = directory, scopes = stored,
                hashes = assets.hashes(), assetDirectory = assetsDirectory))
        return target
    }

    /** Resolves both recorded revisions through Git and compares their independent, immutable static inputs. */
    fun analyze(
        repository: String, baseManifest: Path, headManifest: Path, fold: String, configuration: RepositoryConfiguration,
    ): AnalysisResult {
        val base = readSnapshot(baseManifest)
        val head = readSnapshot(headManifest)
        Files.createDirectories(configuration.cacheDirectory)
        val output = Files.createTempDirectory(configuration.cacheDirectory, "snapshot-analysis-")
        try {
            GitWorkspaceManager(configuration.cacheDirectory.resolve("git"), configuration.maximumWorkspaceSlots,
                configuration.workspaceLeaseTimeout).open(repository, base.sha, head.sha).use { workspace ->
                val (before, after) = prepareSnapshots(base, head, workspace.baseDirectory, workspace.headDirectory, output)
                return GraphFoldEngine(EngineConfiguration(configuration.cacheDirectory.resolve("engine"),
                    configuration.maximumScenarios)).analyze(before, after, fold)
            }
        } finally {
            Files.walk(output).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** Decodes two immutable bundles into the common input format; caller owns [output] and its lifetime. */
    fun prepare(
        baseManifest: Path, headManifest: Path, baseCheckout: Path, headCheckout: Path, output: Path,
    ): Pair<PreparedRevision, PreparedRevision> {
        val base = readSnapshot(baseManifest)
        val head = readSnapshot(headManifest)
        listOf(base to baseCheckout, head to headCheckout).forEach { (record, checkout) ->
            Git.open(checkout.toFile()).use { git ->
                checkInput(git.repository.resolve("HEAD")?.name == record.sha,
                    "Source checkout does not match input snapshot revision ${record.sha}")
            }
        }
        Files.createDirectories(output)
        checkInput(Files.list(output).use { it.findAny().isEmpty }, "Prepared input output directory must be empty")
        return prepareSnapshots(base, head, baseCheckout, headCheckout, output)
    }

    /** Prepares directly compiled source inputs without choosing a compiler, project layout or build tool. */
    fun prepareSource(sha: String, sourceDirectory: Path, output: Path, input: SourceInput): PreparedRevision {
        require(input.javaVersion > 0) { "Build JDK version must be positive" }
        val inventoryInputs = input.dependencies.mapIndexed { index, dependency ->
            if (Files.isDirectory(dependency)) dependency
            else selectedArchiveInventory(dependency, input.javaVersion, output.resolve("inventory/$index.jar"))
        }
        val inventory = captureProductionInventory(input.classes, emptyList(), inventoryInputs, emptyList())
            .copy(resourceDependencies = input.dependencies)
        return prepareRevision(sha, sourceDirectory,
            InputProject(input.classes, input.sourceRoots, input.dependencies, sourceOutputs = input.sourceOutputs,
                production = inventory, javaVersion = input.javaVersion), output.resolve("scope"), DependencyInputCache())
    }
}

private fun prepareSnapshots(
    base: SnapshotRecord, head: SnapshotRecord, baseCheckout: Path, headCheckout: Path, output: Path,
): Pair<PreparedRevision, PreparedRevision> {
    val old = base.toProject(baseCheckout, output.resolve("base"))
    val new = head.toProject(headCheckout, output.resolve("head"))
    val prepared = prepareRevisions(CompiledRevisionInput(base.sha, baseCheckout, old),
        CompiledRevisionInput(head.sha, headCheckout, new), output.resolve("prepared"), DependencyInputCache())
    checkInput(base.sha != head.sha || prepared.first.inventory.hasSameEntries(prepared.second.inventory),
        "Snapshots for the same Git revision contain different static inputs")
    return prepared
}

private fun SnapshotRecord.toProject(checkout: Path, output: Path): InputProject {
    fun source(path: Path): Path = if (path.startsWith(repositoryRoot)) checkout.resolve(repositoryRoot.relativize(path))
        else output.resolve("unmapped-source/${path.hashCode()}")
    val empty = Files.createDirectories(output.resolve("empty"))
    val inputs = scopes.associate { scope ->
        val producers = scope.projectArtifacts + (scope.archive to scope.sourceOutputs.values.flatten().distinct())
        val nested = scope.archive.fileName.toString().endsWith(".war", ignoreCase = true) || isSpringBootArchive(scope.archive)
        val classpath = if (nested) listOf(scope.archive) else listOf(scope.archive) + scope.dependencies
        val archive = ArchiveInput(scope.id, classpath.map {
            ArchiveEntryInput(it, producers[it].orEmpty(), application = it == scope.archive || it in producers)
        }, producerArchives = producers, applicationArchives = producers.keys,
            staticDependencies = if (nested) scope.dependencies else emptyList())
        scope.id to InputProject(empty, scope.sourceRoots.map(::source), scope.dependencies,
            scope.originDirectory, scope.originRunId, scope.sourceOutputs.mapKeys { source(it.key) },
            compilerEvidence = scope.compilerEvidence, sourceClasspaths = scope.sourceClasspaths.mapKeys { source(it.key) },
            declaredSources = scope.declaredSources.map { it.copy(root = source(it.root)) }, archiveInput = archive,
            requireOriginClassHashes = scope.compilerEvidence.isNotEmpty() || scope.originDirectory != null,
            javaVersion = scope.javaVersion)
    }
    return InputProject(empty, emptyList(), emptyList(), analysisScopes = inputs)
}
