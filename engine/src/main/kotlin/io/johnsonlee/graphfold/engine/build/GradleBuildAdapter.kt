package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

internal class GradleBuildAdapter(
    private val timeout: Duration, private val environmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
) {
    fun compile(checkout: Path, output: Path): CompiledProject {
        val extracted = CompilerOriginPlugin.extract(output)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(extracted))
            .joinToString("") { "%02x".format(it) }
        // Stable task inputs permit incremental compilation, UP-TO-DATE and local build-cache restoration.
        val runId = "gradle-v1-$digest"
        val state = Files.createDirectories(checkout.resolve(".gradle/graphfold/$digest"))
        val plugin = state.resolve("compiler-origin-plugin.jar").toAbsolutePath()
        if (!Files.exists(plugin) || Files.mismatch(extracted, plugin) != -1L) {
            Files.copy(extracted, plugin, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
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
            val lines = Files.readAllLines(manifest)
            val entries = lines.filterNot { it.startsWith("sourceOutput|") }.map { line ->
                val parts = line.split('|', limit = 2)
                checkBuild(parts.size == 2, "Invalid Gradle input export")
                parts[0] to Path.of(String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8))
            }
            val outputs = entries.filter { it.first == "classes" }.map { it.second }
            val roots = entries.filter { it.first == "source" }.map { it.second }.filter(Files::isDirectory)
            val dependencies = entries.filter { it.first == "dependency" }.map { it.second }
                .filterNot { it in outputs }.distinct()
            checkBuild(dependencies.all { Files.exists(it) }, "Gradle exported missing dependencies")
            val sourceOutputs = lines.filter { it.startsWith("sourceOutput|") }.map { line ->
                val parts = line.split('|')
                checkBuild(parts.size == SOURCE_OUTPUT_FIELDS, "Invalid Gradle source/output export")
                fun decode(value: String) = Path.of(String(Base64.getDecoder().decode(value), Charsets.UTF_8))
                decode(parts[1]) to decode(parts[2])
            }.groupBy({ it.first }, { it.second })
            return CompiledProject(collectClasses(output, outputs), roots.distinct(), dependencies, origins, runId, sourceOutputs)
        } finally {
            Files.deleteIfExists(init)
        }
    }

}

private const val SOURCE_OUTPUT_FIELDS = 3
