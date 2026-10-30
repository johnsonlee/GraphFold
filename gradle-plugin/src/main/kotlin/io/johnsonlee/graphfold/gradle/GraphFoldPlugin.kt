package io.johnsonlee.graphfold.gradle

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
            task.fold.convention(project.layout.file(fold.map { rootDirectory.file(it).asFile }))
            task.cacheDirectory.convention(project.layout.dir(project.provider {
                java.io.File(System.getProperty("user.home"), ".cache/graphfold")
            }))
            task.jsonReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.json"))
            task.textReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.txt"))
        }
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
