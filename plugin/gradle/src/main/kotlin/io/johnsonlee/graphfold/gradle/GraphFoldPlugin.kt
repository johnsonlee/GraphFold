package io.johnsonlee.graphfold.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/** Exports this build's static inputs and compares explicitly supplied BASE and HEAD snapshots. */
class GraphFoldPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rootDirectory = project.rootProject.layout.projectDirectory
        val export = project.tasks.register("graphFoldInputs", GraphFoldInputs::class.java) { task ->
            task.group = "verification"
            task.description = "Export immutable static inputs from this checkout's Gradle build"
            task.repository.convention(rootDirectory)
            task.outputDirectory.convention(project.layout.dir(project.providers.gradleProperty("graphfold.inputsDirectory")
                .map { rootDirectory.dir(it).asFile }).orElse(project.layout.buildDirectory.dir("graphfold/inputs")))
        }
        project.gradle.projectsEvaluated {
            export.configure { task ->
                val scopes = archiveModels(project.rootProject).map { model ->
                    val scope = model.inputScope(project.objects)
                    task.dependsOn(model.archive, scope.dependencies, scope.sourceOutputs)
                    scope
                }
                task.scopes.set(scopes)
            }
        }
        project.tasks.register("graphFoldCheck", GraphFoldCheck::class.java) { task ->
            task.group = "verification"
            task.description = "Compare exported BASE and HEAD inputs under the selected folding assumptions"
            task.repository.convention(rootDirectory.asFile.absolutePath)
            task.baseInputs.convention(project.layout.file(requiredProperty(project, "graphfold.baseInputs")
                .map { rootDirectory.file(it).asFile }))
            task.headInputs.convention(project.layout.file(requiredProperty(project, "graphfold.headInputs")
                .map { rootDirectory.file(it).asFile }))
            task.fold.convention(project.layout.file(requiredProperty(project, "graphfold.fold")
                .map { rootDirectory.file(it).asFile }))
            task.cacheDirectory.convention(project.layout.buildDirectory.dir("graphfold/cache"))
            task.jsonReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.json"))
            task.textReport.convention(project.layout.buildDirectory.file("reports/graphfold/report.txt"))
        }
    }

    private fun requiredProperty(project: Project, name: String): Provider<String> =
        project.providers.gradleProperty(name).orElse("").map { value ->
            if (value.isBlank()) {
                throw GradleException("Missing Gradle property '$name'. Supply -P$name=<value> or set it in gradle.properties.")
            }
            value
        }
}
