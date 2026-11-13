package io.johnsonlee.graphfold.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.model.ObjectFactory
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService

/** One selected archive and the producers present in its actual configured classpath. */
internal data class GradleArchiveModel(
    val project: Project,
    val archive: Jar,
    val dependencies: Configuration?,
) {
    val identity: String get() = archive.path
    val producers: Map<Project, List<File>> get() {
        val configuration = project.configurations.findByName("runtimeClasspath")
        val archiveClasspath = if (archive.hasProperty("classpath")) archive.property("classpath") as? FileCollection else null
        val packaged = when {
            archiveClasspath != null -> archiveClasspath.files
            configuration != null -> configuration.files
            else -> emptySet()
        }
        val currentBuildPath = configuration?.let {
            val root: ComponentIdentifier = it.incoming.resolutionResult.rootComponent.get().id
            (root as ProjectComponentIdentifier).build.buildPath
        }
        val artifacts = configuration?.let { it.incoming.artifacts.artifacts }.orEmpty().mapNotNull { artifact ->
            val component = artifact.id.componentIdentifier as? ProjectComponentIdentifier
            val producer = if (component != null && component.build.buildPath == currentBuildPath) {
                project.rootProject.findProject(component.projectPath)
            } else null
            producer?.takeIf { artifact.file in packaged }?.let { it to artifact.file }
        }.groupBy({ it.first }, { it.second })
        return artifacts + (project to listOf(archive.archiveFile.get().asFile))
    }

}

/** Reads the already configured build. It never starts another build or changes the checkout. */
internal fun archiveModels(root: Project): List<GradleArchiveModel> {
    val projects = root.allprojects.sortedBy { it.path }
    val applications = projects.flatMap { project ->
        val packaged = listOf("bootJar", "war").mapNotNull { project.tasks.findByName(it) as? Jar }.filter { it.enabled }
        if (packaged.isNotEmpty()) packaged.map { GradleArchiveModel(project, it, null) }
        else if (project.plugins.hasPlugin("application")) {
            // The application plugin applies Java: its jar task cannot be removed or replaced with an incompatible type.
            listOf(project.tasks.named("jar", Jar::class.java).get()).filter { it.enabled }
                .map { GradleArchiveModel(project, it, project.configurations.findByName("runtimeClasspath")) }
        } else emptyList()
    }
    if (applications.isNotEmpty()) return applications
    return projects.flatMap { project ->
        val outgoing = project.configurations.findByName("runtimeElements")
        val archives = outgoing?.outgoing?.artifacts.orEmpty().flatMap { artifact ->
            artifact.buildDependencies.getDependencies(null).filterIsInstance<Jar>()
        }.filter { it.enabled }.distinctBy { it.path }
        archives.map { GradleArchiveModel(project, it, project.configurations.findByName("runtimeClasspath")) }
    }
}

internal fun GradleArchiveModel.inputScope(objects: ObjectFactory): GraphFoldInputScope {
    val scope = objects.newInstance(GraphFoldInputScope::class.java)
    scope.identity.set(this.identity)
    scope.javaVersion.set(project.buildJavaVersion())
    scope.archive.set(this.archive.archiveFile)
    scope.dependencies.from(listOfNotNull(this.dependencies), project.provider { project.staticApiDependencies() })
    val producers = this.producers
    val sourceSets = producers.keys.flatMap { producer ->
        producer.extensions.findByType(SourceSetContainer::class.java).orEmpty().map { producer.path to it }
    }
    val main = sourceSets.filter { it.second.name == "main" }.map { it.second }
    scope.sourceRoots.from(main.flatMap { it.allSource.srcDirs })
    scope.sourceOutputs.from(main.map { it.output.classesDirs })
    scope.sourceClasspath.from(main.map { it.compileClasspath })
    scope.sourceOutputPaths.set(main.flatMap { source ->
        source.allSource.srcDirs.map { root ->
            root.absolutePath to source.output.classesDirs.files.map { it.absolutePath }
        }
    }.toMap())
    scope.sourceClasspathPaths.set(main.flatMap { source ->
        source.allSource.srcDirs.map { root ->
            root.absolutePath to source.compileClasspath.files.map { it.absolutePath }
        }
    }.toMap())
    scope.projectArtifactPaths.set(producers.entries.flatMap { (producer, archives) ->
        val outputs = producer.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")
            ?.output?.classesDirs?.files.orEmpty().map { it.absolutePath }
        archives.map { it.absolutePath to outputs }
    }.toMap())
    scope.declaredSourceRoots.set(sourceSets.associate { (owner, sourceSet) ->
        "$owner/${sourceSet.name}" to sourceSet.allSource.srcDirs.map { it.absolutePath }
    })
    return scope
}

/** Compile-only and WAR provided APIs supply static type evidence without adding project application classes. */
private fun Project.staticApiDependencies(): FileCollection {
    fun archives(name: String): FileCollection {
        val configuration = configurations.findByName(name) ?: return files()
        return configuration.incoming.artifactView { view ->
            view.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.JAR))
        }.files
    }
    // Retain producer task dependencies and request JARs even when compileClasspath normally exposes classes directories.
    return (archives("compileClasspath") - archives("runtimeClasspath")) + archives("providedRuntime")
}

/** The compilation toolchain wins over source/target/release levels and the analyzer's JVM. */
private fun Project.buildJavaVersion(): Provider<Int> {
    val source = extensions.findByType(SourceSetContainer::class.java)?.findByName("main")
    val compileName = if (source == null) "compileJava" else source.compileJavaTaskName
    val compile = tasks.findByName(compileName) as? JavaCompile
    if (compile != null) return compile.javaCompiler.map { it.metadata.languageVersion.asInt() }
    val toolchains = extensions.findByType(JavaToolchainService::class.java)
    val java = extensions.findByType(JavaPluginExtension::class.java)
    return if (toolchains != null && java != null) {
        toolchains.compilerFor(java.toolchain).map { it.metadata.languageVersion.asInt() }
    } else {
        val buildVersion = JavaVersion.current().majorVersion.toInt()
        providers.provider { buildVersion }
    }
}
