package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.johnsonlee.graphfold.engine.cleanupDirectory

/** A loader directory keeps dependency artifacts separate from the application's loose class files. */
internal class AnalysisInputs(artifact: Path, dependencies: List<Path>) : AutoCloseable {
    private val staged = if (dependencies.isEmpty()) null else Files.createTempDirectory("graphfold-classpath-")
    val path: Path = staged ?: artifact

    init {
        if (staged != null) {
            var complete = false
            try {
                stageArtifact(artifact, staged)
                val indexWidth = dependencies.lastIndex.toString().length
                dependencies.forEachIndexed { index, dependency ->
                    val name = "dependency-${index.toString().padStart(indexWidth, '0')}.jar"
                    stageDependency(dependency, staged.resolve(name))
                }
                complete = true
            } finally {
                if (!complete) close()
            }
        }
    }

    private fun stageDependency(dependency: Path, target: Path) {
        if (Files.isDirectory(dependency)) {
            archiveDependency(dependency, target)
        } else {
            require(Files.isRegularFile(dependency)) { "Dependency must be a JAR or classes directory: $dependency" }
            Files.createSymbolicLink(target, dependency.toAbsolutePath())
        }
    }

    private fun archiveDependency(dependency: Path, target: Path) {
        val root = dependency.toRealPath()
        // CREATE_NEW also prevents writing through an application-owned symlink at the reserved staging name.
        ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { zip ->
            Files.walk(root).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    val name = root.relativize(file).joinToString("/")
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
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
        staged?.let { cleanupDirectory(it) }
    }
}
