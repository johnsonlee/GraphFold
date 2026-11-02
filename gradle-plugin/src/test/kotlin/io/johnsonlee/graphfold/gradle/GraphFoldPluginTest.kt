package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_TIMEOUT
import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import java.nio.file.Path
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphFoldPluginTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `properties supplied after plugin application are read lazily with root relative fold paths`() {
        val root = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val child = ProjectBuilder.builder().withName("service").withParent(root).build()
        val properties = applyWithPropertyProviders(child)
        properties.getValue("graphfold.base").set("base-sha")
        properties.getValue("graphfold.head").set("head-sha")
        properties.getValue("graphfold.fold").set("rules/fold.yml")
        val task = child.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        assertEquals("base-sha", task.base.get())
        assertEquals("head-sha", task.head.get())
        assertEquals(root.rootDir.resolve("rules/fold.yml"), task.fold.get().asFile)
        assertEquals(root.rootDir.absolutePath, task.repository.get())
        // Task API overrides must continue to take precedence over convention providers.
        task.base.set("explicit-base")
        assertEquals("explicit-base", task.base.get())
        assertEquals("head-sha", task.head.get())
    }

    @Test
    fun `missing and whitespace properties fail on input read with actionable property names`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val properties = applyWithPropertyProviders(project)
        properties.getValue("graphfold.head").set("   ")
        // Applying the plugin and realizing its task must not require analysis inputs.
        val task = project.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        listOf("graphfold.base" to { task.base.get() }, "graphfold.head" to { task.head.get() },
            "graphfold.fold" to { task.fold.get() }).forEach { (property, read) ->
            val failure = assertFails { read() }
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            assertTrue("Missing Gradle property '$property'" in messages, messages)
            assertTrue("-P$property=<value>" in messages, messages)
        }
    }

    @Test
    fun `optional settings remain lazy and custom lease timeout overrides build derived default`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val properties = applyWithPropertyProviders(project)
        val task = project.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        properties.getValue("graphfold.workspaceSlots").set("4")
        properties.getValue("graphfold.buildTimeout").set("1800")
        properties.getValue("graphfold.buildEnv").set("ARTIFACT_TOKEN, BUILD_USER,ARTIFACT_TOKEN")
        assertEquals(4, task.workspaceSlots.get())
        assertEquals(1800L, task.buildTimeout.get())
        assertEquals(3900L, task.leaseTimeout.get())
        assertEquals(DEFAULT_BUILD_ENVIRONMENT + setOf("ARTIFACT_TOKEN", "BUILD_USER"), task.buildEnv.get())
        properties.getValue("graphfold.leaseTimeout").set("120")
        assertEquals(120L, task.leaseTimeout.get())
        properties.getValue("graphfold.buildTimeout").set(Int.MAX_VALUE.toString())
        properties.getValue("graphfold.leaseTimeout").set(null as String?)
        assertEquals(Int.MAX_VALUE.toLong(), task.buildTimeout.get())
        assertEquals(4294967594L, task.leaseTimeout.get())
        task.buildTimeout.set(60L)
        assertEquals(420L, task.leaseTimeout.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "abc", "1.5", "2147483648", ""])
    fun `capacity settings reject invalid positive integers`(value: String) {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val properties = applyWithPropertyProviders(project)
        val task = project.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        listOf(
            "graphfold.workspaceSlots" to task.workspaceSlots,
            "graphfold.buildTimeout" to task.buildTimeout,
            "graphfold.leaseTimeout" to task.leaseTimeout,
        ).forEach { (name, property) ->
            properties.getValue(name).set(value)
            val failure = assertFails { property.get() }
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            assertTrue("'$name' must be a positive integer" in messages, messages)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "TOKEN,", ",TOKEN", "TOKEN,,OTHER", "TOKEN,   ,OTHER", "TOKEN=secret", "9TOKEN", "A-B"])
    fun `environment property rejects values invalid names and empty entries`(value: String) {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val properties = applyWithPropertyProviders(project)
        val task = project.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        properties.getValue("graphfold.buildEnv").set(value)
        val failure = assertFails { task.buildEnv.get() }
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertTrue("'graphfold.buildEnv' must be comma-separated environment variable names" in messages, messages)
        assertTrue("secret" !in messages, messages)
    }

    private fun applyWithPropertyProviders(project: Project): Map<String, Property<String>> {
        // TestKit verifies real -P/property-file loading. Here real mutable Gradle providers isolate
        // lazy convention behavior from ProjectBuilder's absent settings/property-loading lifecycle.
        val properties = listOf(
            "graphfold.base", "graphfold.head", "graphfold.fold", "graphfold.workspaceSlots",
            "graphfold.buildTimeout", "graphfold.leaseTimeout", "graphfold.buildEnv",
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
    fun `plugin registers only an explicit task with repository and output defaults`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("base")
        project.pluginManager.apply(GraphFoldPlugin::class.java)
        val task = project.tasks.getByName("graphFoldCheck") as GraphFoldCheck
        assertNull(project.extensions.findByName("graphfold"))
        assertEquals(project.rootDir.absolutePath, task.repository.get())
        assertEquals(DEFAULT_MAXIMUM_WORKSPACE_SLOTS, task.workspaceSlots.get())
        assertEquals(DEFAULT_BUILD_TIMEOUT.seconds, task.buildTimeout.get())
        assertEquals(defaultWorkspaceLeaseTimeout(DEFAULT_BUILD_TIMEOUT).seconds, task.leaseTimeout.get())
        assertEquals(DEFAULT_BUILD_ENVIRONMENT, task.buildEnv.get())
        assertEquals("verification", task.group)
        assertTrue(project.tasks.getByName("check").taskDependencies.getDependencies(null).none { it.name == task.name })
        assertEquals("report.json", task.jsonReport.get().asFile.name)
        assertEquals("report.txt", task.textReport.get().asFile.name)
        assertTrue(task.cacheDirectory.get().asFile.path.endsWith(".cache/graphfold"))
    }
}
