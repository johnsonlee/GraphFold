package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Path
import java.time.Duration

internal class GradleBuildAdapter(
    private val timeout: Duration, private val environmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
) {
    fun compile(checkout: Path, output: Path): CompiledProject {
        val plugin = CompilerOriginPlugin.artifact()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(plugin))
            .joinToString("") { "%02x".format(it) }
        // Stable task inputs permit incremental compilation, UP-TO-DATE and local build-cache restoration.
        val runId = "gradle-v1-$digest"
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
                    "-Dgraphfold.origin.output=$origins", "-Dgraphfold.origin.runId=$runId", ":graphfoldExportCompiledInputs"),
                timeout, environmentAllowlist,
            )
            checkBuild(Files.exists(manifest), "Gradle did not export analysis inputs")
            val entries = parseGradleInputs(Files.readAllLines(manifest))
            val outputs = entries.filter { it.kind == GradleInputKind.CLASSES }.map { it.paths.single() }
            val roots = entries.filter { it.kind == GradleInputKind.SOURCE }.map { it.paths.single() }.filter(Files::isDirectory)
            val dependencies = entries.filter { it.kind == GradleInputKind.DEPENDENCY }.map { it.paths.single() }
                .filterNot { it in outputs }.distinct()
            checkBuild(dependencies.all { Files.exists(it) }, "Gradle exported missing dependencies")
            val sourceOutputs = entries.filter { it.kind == GradleInputKind.SOURCE_OUTPUT }
                .groupBy({ it.paths[0] }, { it.paths[1] })
            return CompiledProject(collectClasses(output, outputs), roots.distinct(), dependencies, origins, runId, sourceOutputs)
        } finally {
            Files.deleteIfExists(init)
        }
    }

}
