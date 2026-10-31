package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import java.time.Duration
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

class GraphFoldPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootDirectory = project.rootProject.layout.projectDirectory
        val base = requiredProperty(project, "graphfold.base")
        val head = requiredProperty(project, "graphfold.head")
        val fold = requiredProperty(project, "graphfold.fold")
        project.tasks.register("graphFoldCheck", GraphFoldCheck::class.java) { task ->
            task.group = "verification"
            task.description = "Detect code changes that survive the specified folding assumptions"
            task.repository.convention(rootDirectory.asFile.absolutePath)
            task.base.convention(base)
            task.head.convention(head)
            task.workspaceSlots.convention(optionalPositiveNumber(project, "graphfold.workspaceSlots")
                .map { it.toInt() }.orElse(DEFAULT_MAXIMUM_WORKSPACE_SLOTS))
            task.buildTimeout.convention(optionalPositiveNumber(project, "graphfold.buildTimeout").orElse(DEFAULT_BUILD_TIMEOUT.seconds))
            task.leaseTimeout.convention(optionalPositiveNumber(project, "graphfold.leaseTimeout").orElse(
                task.buildTimeout.map { defaultWorkspaceLeaseTimeout(Duration.ofSeconds(it)).seconds },
            ))
            task.buildEnv.convention(project.providers.gradleProperty("graphfold.buildEnv").map { value ->
                val names = value.split(',').map(String::trim)
                if (names.any { !ENVIRONMENT_NAME.matches(it) }) {
                    throw GradleException(
                        "Gradle property 'graphfold.buildEnv' must be comma-separated environment variable names " +
                            "without values or empty entries.",
                    )
                }
                DEFAULT_BUILD_ENVIRONMENT + names
            }.orElse(DEFAULT_BUILD_ENVIRONMENT))
            task.fold.convention(project.layout.file(fold.map { rootDirectory.file(it).asFile }))
            task.cacheDirectory.convention(project.layout.dir(project.provider {
                java.io.File(System.getProperty("user.home"), ".cache/graphfold")
            }))
            task.jsonReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.json"))
            task.textReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.txt"))
        }
    }

    private fun optionalPositiveNumber(project: Project, name: String): Provider<Long> =
        project.providers.gradleProperty(name).map { value ->
            val number = value.toIntOrNull()
            if (number == null || number <= 0) {
                throw GradleException("Gradle property '$name' must be a positive integer no greater than ${Int.MAX_VALUE}.")
            }
            number.toLong()
        }

    private fun requiredProperty(project: Project, name: String): Provider<String> =
        project.providers.gradleProperty(name).orElse("").map { value ->
            if (value.isBlank()) {
                throw GradleException(
                    "Missing Gradle property '$name'. Supply -P$name=<value> or set it in gradle.properties.",
                )
            }
            value
        }
}

private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
