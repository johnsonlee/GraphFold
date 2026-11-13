package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.git.GitWorkspaceManager
import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner

/** Test orchestration only: each revision configures its own real Gradle model and exports normal producer outputs. */
internal class PackagedModelAnalyzer(private val cache: Path) {
    private val snapshots = mutableMapOf<Pair<String, String>, Pair<Path, Path>>()

    fun analyze(request: AnalysisRequest): AnalysisResult {
        val manifests = snapshots.getOrPut(request.base to request.head) {
            GitWorkspaceManager(cache.resolve("export/git")).open(request.repository, request.base, request.head).use { workspace ->
                val output = Files.createTempDirectory(Files.createDirectories(cache.resolve("snapshots")), "pair-")
                val base = export(workspace.baseDirectory, output.resolve("base"))
                val head = export(workspace.headDirectory, output.resolve("head"))
                base to head
            }
        }
        return StaticInputSnapshots.analyze(request.repository, manifests.first, manifests.second, request.fold,
            RepositoryConfiguration(cache.resolve("analysis")))
    }

    private fun export(checkout: Path, destination: Path): Path {
        GradleRunner.create().withProjectDir(checkout.toFile()).withPluginClasspath()
            .withArguments("graphFoldInputs", "-Pgraphfold.inputsDirectory=$destination", "--stacktrace", "--max-workers=1").build()
        return destination.resolve("inputs.json")
    }
}

/** Fixtures commit plugin application along with their build model; no checkout-time script rewriting is allowed. */
internal fun ensureSnapshotPlugin(repository: Path) {
    val script = repository.resolve("build.gradle")
    val source = Files.readString(script)
    if ("id 'io.johnsonlee.graphfold'" !in source) {
        Files.writeString(script, "plugins { id 'io.johnsonlee.graphfold' }\n" + source)
    }
}
