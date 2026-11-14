package io.johnsonlee.graphfold.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaCompiler
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleArchiveModelTest {
    @Test
    fun `archive only toolchain service without Java model falls back to the actual Gradle JVM`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("jvm-toolchains")
        val jar = project.tasks.register("bundle", Jar::class.java).get().apply {
            destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
            archiveFileName.set("bundle.jar")
        }
        jar.extensions.extraProperties.set("classpath", "unrelated metadata")
        val model = GradleArchiveModel(project, jar, null)
        assertEquals(JavaVersion.current().majorVersion.toInt(), model.inputScope(project.objects).javaVersion.get())
        assertEquals(setOf(project), model.producers.keys)
    }

    @Test
    fun `disabled application artifact contributes no invented fallback scope`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("application")
        project.tasks.named("jar").get().enabled = false
        assertTrue(archiveModels(project).isEmpty())
    }

    @Test
    fun `included build artifacts do not borrow source ownership from a same named current project`() {
        val project = mock(Project::class.java, RETURNS_DEEP_STUBS)
        val configuration = mock(Configuration::class.java, RETURNS_DEEP_STUBS)
        val artifact = mock(ResolvedArtifactResult::class.java, RETURNS_DEEP_STUBS)
        val component = mock(ProjectComponentIdentifier::class.java, RETURNS_DEEP_STUBS)
        val archive = mock(Jar::class.java, RETURNS_DEEP_STUBS)
        val library = File("included-library.jar")
        val application = File("application.jar")
        `when`(project.configurations.findByName("runtimeClasspath")).thenReturn(configuration)
        `when`(configuration.files).thenReturn(setOf(library))
        `when`(configuration.incoming.artifacts.artifacts).thenReturn(setOf(artifact))
        `when`(artifact.id.componentIdentifier).thenReturn(component)
        `when`(artifact.file).thenReturn(library)
        val current = mock(ProjectComponentIdentifier::class.java, RETURNS_DEEP_STUBS)
        `when`(configuration.incoming.resolutionResult.rootComponent.get().id).thenReturn(current)
        `when`(current.build.buildPath).thenReturn(":")
        `when`(component.build.buildPath).thenReturn(":included")
        `when`(archive.archiveFile.get().asFile).thenReturn(application)
        assertEquals(mapOf(project to listOf(application)), GradleArchiveModel(project, archive, configuration).producers)
    }

    @Test
    fun `project API dependencies use producer jars without adding application ownership`() {
        val root = ProjectBuilder.builder().build()
        val web = ProjectBuilder.builder().withName("web").withParent(root).build()
        web.pluginManager.apply("war")
        val dependencies = listOf("contracts" to "compileOnly", "provided" to "providedCompile", "business" to "implementation")
        val jars = dependencies.associate { (name, configuration) ->
            val producer = ProjectBuilder.builder().withName(name).withParent(root).build()
            producer.pluginManager.apply("java-library")
            web.dependencies.add(configuration, web.dependencies.project(mapOf("path" to producer.path)))
            name to producer.tasks.named("jar", Jar::class.java).get()
        }
        val scope = archiveModels(root).single().inputScope(root.objects)
        val apiTasks = setOf(jars.getValue("contracts"), jars.getValue("provided"))
        assertEquals(apiTasks.map { it.archiveFile.get().asFile }.toSet(), scope.dependencies.files)
        assertTrue(scope.dependencies.buildDependencies.getDependencies(null).containsAll(apiTasks))
        val war = web.tasks.named("war", Jar::class.java).get()
        assertEquals(setOf(war.archiveFile.get().asFile.absolutePath, jars.getValue("business").archiveFile.get().asFile.absolutePath),
            scope.projectArtifactPaths.get().keys)
        assertTrue(scope.sourceRoots.files.none { it.path.contains("/contracts/") || it.path.contains("/provided/") })
    }

    @Test
    fun `WAR provided and compile only APIs supplement packaged bytes without duplicating packaged libraries`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("war")
        val servlet = project.file("servlet-api.jar")
        val annotations = project.file("annotations.jar")
        val packaged = project.file("business.jar")
        project.dependencies.add("providedCompile", project.files(servlet))
        project.dependencies.add("compileOnly", project.files(annotations))
        project.dependencies.add("implementation", project.files(packaged))
        val scope = archiveModels(project).single().inputScope(project.objects)
        assertEquals(setOf(servlet, annotations), scope.dependencies.files)
        assertEquals(setOf(project.tasks.named("war", Jar::class.java).get().archiveFile.get().asFile.absolutePath),
            scope.projectArtifactPaths.get().keys)
    }

    @Test
    fun `selected compiler metadata controls the exported JDK rather than bytecode target or analyzer JVM`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-library")
        val compile = project.tasks.named("compileJava", JavaCompile::class.java).get()
        compile.options.release.set(11)
        val compiler = mock(JavaCompiler::class.java)
        val metadata = mock(JavaInstallationMetadata::class.java)
        `when`(compiler.metadata).thenReturn(metadata)
        `when`(metadata.languageVersion).thenReturn(JavaLanguageVersion.of(17))
        compile.javaCompiler.set(compiler)
        val input = archiveModels(project).single().inputScope(project.objects)
        assertEquals(17, input.javaVersion.get())
        assertEquals(17, input.input().javaVersion)
    }

    @Test
    fun `archive only project retains its explicitly selected Gradle toolchain without requiring a compile task`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-base")
        project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        val jar = project.tasks.register("bundle", Jar::class.java).get()
        val input = GradleArchiveModel(project, jar, null).inputScope(project.objects)
        assertEquals(17, input.javaVersion.get())
        assertTrue(project.tasks.findByName("compileJava") == null)
    }

    @Test
    fun `plain libraries use their published jar and declared dependencies without an application plugin`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-library")
        val scopes = archiveModels(project)
        assertEquals(listOf(":jar"), scopes.map { it.identity })
        assertEquals("runtimeClasspath", scopes.single().dependencies?.name)
        assertTrue(scopes.single().inputScope(project.objects).sourceRoots.files.any { it.path.endsWith("src/main/java") })
    }

    @Test
    fun `packaged boot and war archives replace separate library scopes`() {
        val root = ProjectBuilder.builder().build()
        val library = ProjectBuilder.builder().withName("library").withParent(root).build()
        library.pluginManager.apply("java-library")
        val boot = ProjectBuilder.builder().withName("boot").withParent(root).build()
        boot.pluginManager.apply("base")
        boot.tasks.register("bootJar", Jar::class.java)
        val war = ProjectBuilder.builder().withName("web").withParent(root).build()
        war.pluginManager.apply("war")
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        app.pluginManager.apply("application")
        val scopes = archiveModels(root)
        assertEquals(listOf(":app:jar", ":boot:bootJar", ":web:war"), scopes.map { it.identity })
        assertTrue(scopes.drop(1).all { it.dependencies == null })
        val archiveOnly = scopes[1].inputScope(root.objects)
        assertTrue(archiveOnly.sourceRoots.files.isEmpty())
        assertEquals(JavaVersion.current().majorVersion.toInt(), archiveOnly.javaVersion.get())
    }

    @Test
    fun `disabled archives and projects without outgoing archives do not invent inputs`() {
        val project = ProjectBuilder.builder().build()
        assertTrue(archiveModels(project).isEmpty())
        project.pluginManager.apply("java")
        project.tasks.named("jar").get().enabled = false
        project.tasks.register("bootJar", Jar::class.java) { it.enabled = false }
        assertTrue(archiveModels(project).isEmpty())
    }
}
