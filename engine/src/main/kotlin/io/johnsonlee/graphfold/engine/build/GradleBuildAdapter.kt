package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Path
import java.time.Duration

internal class GradleBuildAdapter(
    private val timeout: Duration, private val environmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
    private val initScript: () -> java.io.InputStream? = { GradleBuildAdapter::class.java.getResourceAsStream("graphfold.gradle") },
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
            checkNotNull(initScript()) {
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
            val sourceOutputs = entries.filter { it.kind == GradleInputKind.SOURCE_OUTPUT }
                .groupBy({ it.paths[0] }, { it.paths[1] })
            val classes = collectClasses(output, outputs)
            val dependencies = gradleAnalysisDependencies(entries, classes)
            val production = productionInventory(entries, classes)
            return CompiledProject(classes, roots.distinct(), dependencies, origins, runId, sourceOutputs, production)
        } finally {
            Files.deleteIfExists(init)
        }
    }

    private fun productionInventory(entries: List<GradleInput>, classes: Path): ProductionInventory? {
        val complete = entries.count { it.kind == GradleInputKind.PRODUCTION_COMPLETE }
        if (complete == 0) return null
        checkBuild(complete == 1, "Gradle exported conflicting production inventories")
        val resources = entries.filter { it.kind == GradleInputKind.RESOURCE }.map { it.paths.single() }
            .distinct()
        val runtime = entries.filter { it.kind == GradleInputKind.RUNTIME_DEPENDENCY }.map { it.paths.single() }
        val artifacts = entries.filter { it.kind == GradleInputKind.ARTIFACT }.map { it.paths.single() }.distinct()
        checkBuild(artifacts.isNotEmpty(), "Gradle did not export production runtime artifacts")
        val inventory = captureProductionInventory(classes, resources, runtime, artifacts)
        val groups = runtimeClasspathGroups(entries)
        return inventory.copy(entries = inventory.entries + groups, nonClassEntries = inventory.nonClassEntries + groups,
            resourceIndependentEntries = inventory.resourceIndependentEntries + groups)
    }
}

/** Preserve per-project class-loader search groups independently of checkout locations. */
internal fun runtimeClasspathGroups(entries: List<GradleInput>): Map<String, String> {
    val projects = mutableSetOf<String>()
    val groups = linkedMapOf<String, String>()
    var current: String? = null
    entries.forEach { entry ->
        when (entry.kind) {
            GradleInputKind.RUNTIME_CLASSPATH -> {
                val project = entry.text.single()
                checkBuild(project.startsWith(":") && projects.add(project), "Invalid or duplicate Gradle runtime classpath project")
                val key = "gradle/runtimeClasspath/${groups.size}/$project"
                current = key
                groups[key] = "0"
            }
            GradleInputKind.RUNTIME_DEPENDENCY -> {
                val key = current ?: throw BuildFailure("Gradle runtime dependency has no classpath project")
                groups[key] = (groups.getValue(key).toInt() + 1).toString()
            }
            else -> Unit
        }
    }
    checkBuild(groups.isNotEmpty(), "Gradle exported no runtime classpath projects")
    return groups
}
