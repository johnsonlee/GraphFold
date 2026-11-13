package io.johnsonlee.graphfold.maven

import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MavenPluginDescriptorTest {
    @Test fun `Maven descriptor exposes constructible goals with injected current model`() {
        val resource = assertNotNull(javaClass.getResourceAsStream("/META-INF/maven/plugin.xml"))
        val xml = resource.reader().use { it.readText() }
        val descriptor = PluginDescriptorBuilder().build(xml.reader())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("17", document.getElementsByTagName("requiredJavaVersion").item(0).textContent)
        assertEquals("3.9.9", document.getElementsByTagName("requiredMavenVersion").item(0).textContent)
        assertEquals("graphfold", descriptor.goalPrefix)
        assertEquals("io.johnsonlee.graphfold", descriptor.groupId)
        assertEquals("maven-plugin", descriptor.artifactId)
        assertTrue(!descriptor.version.contains("@"))
        assertEquals(setOf("snapshot", "check"), descriptor.mojos.map { it.goal }.toSet())
        descriptor.mojos.forEach { mojo ->
            assertEquals("verify", mojo.phase)
            assertEquals("compile+runtime", mojo.dependencyResolutionRequired)
            assertTrue(mojo.isThreadSafe)
            assertFalse(mojo.isAggregator)
            assertTrue(mojo.isProjectRequired)
            assertTrue(GraphFoldSnapshotMojo::class.java.getDeclaredField("toolchainManager")
                .isAnnotationPresent(Inject::class.java))
            assertIs<AbstractMojo>(Class.forName(mojo.implementation).getDeclaredConstructor().newInstance())
            val parameters = mojo.parameters.associateBy { it.name }
            assertEquals("org.apache.maven.project.MavenProject", parameters.getValue("project").type)
            assertEquals("org.apache.maven.execution.MavenSession", parameters.getValue("session").type)
            val common = setOf("project", "session", "snapshotDirectory")
            assertEquals(if (mojo.goal == "check") common + setOf("baseSnapshot", "rules", "report") else common, parameters.keys)
            parameters.values.forEach { assertTrue(it.isRequired, it.name) }
            listOf("project", "session").forEach { assertFalse(parameters.getValue(it).isEditable, it) }
            val configuration = mojo.mojoConfiguration
            assertEquals("\${project}", configuration.getChild("project").getAttribute("default-value"))
            assertEquals("\${session}", configuration.getChild("session").getAttribute("default-value"))
            assertEquals("\${project.build.directory}/graphfold/snapshot",
                configuration.getChild("snapshotDirectory").getAttribute("default-value"))
            assertEquals("\${graphfold.snapshotDirectory}", configuration.getChild("snapshotDirectory").value)
            if (mojo.goal == "check") {
                assertEquals("\${graphfold.baseSnapshot}", configuration.getChild("baseSnapshot").value)
                assertEquals("\${session.topLevelProject.basedir}/fold-rules.yml",
                    configuration.getChild("rules").getAttribute("default-value"))
                assertEquals("\${graphfold.rules}", configuration.getChild("rules").value)
                assertEquals("\${project.build.directory}/graphfold/report.json",
                    configuration.getChild("report").getAttribute("default-value"))
                assertEquals("\${graphfold.report}", configuration.getChild("report").value)
            }
        }
    }
}
