package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

internal class GradleBuildAdapter(private val timeout: Duration) {
    fun compile(checkout: Path, output: Path): CompiledProject {
        val plugin = CompilerOriginPlugin.extract(output)
        val runId = CompilerOriginPlugin.runId(output)
        val origins = output.resolve("origins").toAbsolutePath()
        val manifest = output.resolve("gradle-inputs.txt").toAbsolutePath()
        val init = Files.createTempFile(output, "graphfold-", ".gradle")
        try {
            checkNotNull(javaClass.getResourceAsStream("graphfold.gradle")) {
                "Missing bundled graphfold.gradle init script"
            }.use { script -> Files.copy(script, init, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            runBuild(
                checkout, output.resolve("build.log"),
                listOf("sh", "./gradlew", "--no-daemon", "--no-configuration-cache", "--init-script", init.toString(),
                    "-Dgraphfold.export.path=$manifest", "-Dgraphfold.origin.plugin=$plugin",
                    "-Dgraphfold.origin.output=$origins", "-Dgraphfold.origin.runId=$runId", ":graphfoldExportCompiledInputs"), timeout,
            )
            checkBuild(Files.exists(manifest), "Gradle did not export analysis inputs")
            val entries = Files.readAllLines(manifest).map { line ->
                val parts = line.split('|', limit = 2)
                checkBuild(parts.size == 2, "Invalid Gradle input export")
                parts[0] to Path.of(String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8))
            }
            val outputs = entries.filter { it.first == "classes" }.map { it.second }
            val roots = entries.filter { it.first == "source" }.map { it.second }.filter(Files::isDirectory)
            val dependencies = entries.filter { it.first == "dependency" }.map { it.second }
                .filterNot { it in outputs }.distinct()
            checkBuild(dependencies.all { Files.exists(it) }, "Gradle exported missing dependencies")
            return CompiledProject(collectClasses(output, outputs), roots.distinct(), dependencies, origins, runId)
        } finally {
            Files.deleteIfExists(init)
        }
    }

}
