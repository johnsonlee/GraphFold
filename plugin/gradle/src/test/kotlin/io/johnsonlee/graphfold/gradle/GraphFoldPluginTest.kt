package io.johnsonlee.graphfold.gradle

import java.nio.file.Path
import java.nio.file.Files
import java.util.jar.JarOutputStream
import org.eclipse.jgit.api.Git
import org.gradle.api.Action
import org.gradle.api.invocation.Gradle
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.any
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphFoldPluginTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `properties supplied after plugin application are read lazily with root relative fold paths`() {
        val root = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val child = ProjectBuilder.builder().withName("service").withParent(root).build()
        val properties = applyWithPropertyProviders(child)
        properties.getValue("graphfold.baseInputs").set("snapshots/base.json")
        properties.getValue("graphfold.headInputs").set("snapshots/head.json")
        properties.getValue("graphfold.fold").set("rules/fold.yml")
        properties.getValue("graphfold.inputsDirectory").set("exports")
        val export = child.tasks.named("graphFoldInputs", GraphFoldInputs::class.java).get()
        assertEquals(root.rootDir.resolve("exports"), export.outputDirectory.get().asFile)
        val task = child.tasks.named("graphFoldCheck", GraphFoldCheck::class.java).get()
        assertEquals(root.rootDir.resolve("snapshots/base.json"), task.baseInputs.get().asFile)
        assertEquals(root.rootDir.resolve("snapshots/head.json"), task.headInputs.get().asFile)
        assertEquals(root.rootDir.resolve("rules/fold.yml"), task.fold.get().asFile)
        assertEquals(root.rootDir.absolutePath, task.repository.get())
        // Task API overrides must continue to take precedence over convention providers.
        task.baseInputs.set(root.layout.projectDirectory.file("explicit-base"))
        assertEquals(root.rootDir.resolve("explicit-base"), task.baseInputs.get().asFile)
        assertEquals(root.rootDir.resolve("snapshots/head.json"), task.headInputs.get().asFile)
    }

    @Test
    fun `missing and whitespace properties fail on input read with actionable property names`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val properties = applyWithPropertyProviders(project)
        properties.getValue("graphfold.headInputs").set("   ")
        // Applying the plugin and realizing its task must not require analysis inputs.
        val task = project.tasks.named("graphFoldCheck", GraphFoldCheck::class.java).get()
        listOf("graphfold.baseInputs" to { task.baseInputs.get() }, "graphfold.headInputs" to { task.headInputs.get() },
            "graphfold.fold" to { task.fold.get() }).forEach { (property, read) ->
            val failure = assertFails { read() }
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            assertTrue("Missing Gradle property '$property'" in messages, messages)
            assertTrue("-P$property=<value>" in messages, messages)
        }
    }

    @Test
    fun `configured build lifecycle exports immutable model inputs through the real task action`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.resolve("repository").toFile()).build()
        project.pluginManager.apply("java-library")
        Files.writeString(project.projectDir.toPath().resolve("README"), "committed")
        Files.writeString(project.projectDir.toPath().resolve(".gitignore"), "build/\n.gradle/\n")
        Git.init().setDirectory(project.projectDir).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("input").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call()
        }
        val artifact = project.tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile.toPath()
        Files.createDirectories(artifact.parent)
        JarOutputStream(Files.newOutputStream(artifact)).close()
        val gradle = mock(Gradle::class.java, delegatesTo<Any>(project.gradle))
        val callbacks = mutableListOf<Action<Gradle>>()
        doAnswer { invocation ->
            callbacks += invocation.getArgument<Action<Gradle>>(0)
            null
        }.`when`(gradle).projectsEvaluated(any<Action<Gradle>>())
        val view = mock(Project::class.java, delegatesTo<Any>(project))
        doReturn(gradle).`when`(view).gradle
        GraphFoldPlugin().apply(view)
        callbacks.single().execute(gradle)
        val task = project.tasks.named("graphFoldInputs", GraphFoldInputs::class.java).get()
        task.outputDirectory.set(temporary.resolve("snapshot").toFile())
        assertEquals(listOf(":jar"), task.scopes.get().map { it.identity.get() })
        task.export()
        val manifest = task.outputDirectory.file("inputs.json").get().asFile.toPath()
        assertTrue(Files.isRegularFile(manifest))
        assertTrue(Files.readString(manifest).contains("repositoryRoot"))
    }

    private fun applyWithPropertyProviders(project: Project): Map<String, Property<String>> {
        // TestKit verifies real -P/property-file loading. Here real mutable Gradle providers isolate
        // lazy convention behavior from ProjectBuilder's absent settings/property-loading lifecycle.
        val properties = listOf(
            "graphfold.baseInputs", "graphfold.headInputs", "graphfold.fold", "graphfold.inputsDirectory",
        ).associateWith {
            project.objects.property(String::class.java)
        }
        val providers = mock(ProviderFactory::class.java)
        properties.forEach { (name, property) -> `when`(providers.gradleProperty(name)).thenReturn(property) }
        val view = mock(Project::class.java, delegatesTo<Any>(project))
        doReturn(providers).`when`(view).providers
        GraphFoldPlugin().apply(view)
        return properties
    }

    @Test
    fun `plugin registers explicit export and check tasks with repository and output defaults`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("base")
        project.pluginManager.apply(GraphFoldPlugin::class.java)
        val task = project.tasks.named("graphFoldCheck", GraphFoldCheck::class.java).get()
        assertNull(project.extensions.findByName("graphfold"))
        assertTrue(project.tasks.named("graphFoldInputs").get() is GraphFoldInputs)
        assertEquals(project.rootDir.absolutePath, task.repository.get())
        assertEquals("verification", task.group)
        assertTrue(project.tasks.getByName("check").taskDependencies.getDependencies(null).none { it.name == task.name })
        assertEquals("report.json", task.jsonReport.get().asFile.name)
        assertEquals("report.txt", task.textReport.get().asFile.name)
        assertTrue(task.cacheDirectory.get().asFile.path.endsWith("build/graphfold/cache"))
    }
}
