package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** A loader directory exposes libraries for resolution without changing the application inventory. */
internal class AnalysisInputs(artifact: Path, dependencies: List<Path>) : AutoCloseable {
    private val staged = if (dependencies.isEmpty()) null else Files.createTempDirectory("graphfold-classpath-")
    val path: Path = staged ?: artifact

    init {
        if (staged != null) {
            try {
                stageArtifact(artifact, staged)
                dependencies.forEachIndexed { index, dependency ->
                    stageDependency(dependency, index, staged)
                }
            } catch (failure: java.io.IOException) {
                close()
                throw failure
            } catch (failure: IllegalArgumentException) {
                close()
                throw failure
            }
        }
    }

    private fun stageDependency(dependency: Path, index: Int, target: Path) {
        if (Files.isDirectory(dependency)) {
            stageArtifact(dependency, target)
        } else {
            require(Files.isRegularFile(dependency)) { "Dependency must be a JAR or classes directory: $dependency" }
            Files.createSymbolicLink(target.resolve("dependency-$index.jar"), dependency.toAbsolutePath())
        }
    }

    private fun stageArtifact(artifact: Path, target: Path) {
        if (!Files.isDirectory(artifact)) {
            Files.createSymbolicLink(target.resolve("application.jar"), artifact.toAbsolutePath())
            return
        }
        Files.walk(artifact).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val output = target.resolve(artifact.relativize(file))
                Files.createDirectories(output.parent)
                Files.createSymbolicLink(output, file.toAbsolutePath())
            }
        }
    }

    override fun close() {
        staged?.let { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
