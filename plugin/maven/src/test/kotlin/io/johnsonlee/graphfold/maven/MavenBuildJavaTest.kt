package io.johnsonlee.graphfold.maven

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Model
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.apache.maven.toolchain.Toolchain
import org.apache.maven.toolchain.ToolchainManager
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class MavenBuildJavaTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("17.0.12,17", "21.0.5,21", "1.8.0_402,8")
    fun `ordinary compilation uses recorded Maven session JDK not analyzer JVM`(version: String, feature: Int) {
        val project = project().apply {
            properties.setProperty("maven.compiler.release", "8")
            properties.setProperty("maven.compiler.target", "8")
        }
        assertEquals(feature, buildJavaVersion(project, session(version), mock(ToolchainManager::class.java)))
    }

    @Test fun `selected session toolchain controls MR selection instead of Maven host JDK`() {
        val session = session("17.0.12")
        val manager = mock(ToolchainManager::class.java)
        val selected = toolchain("selected", "21.0.5")
        `when`(manager.getToolchainFromBuildContext("jdk", session)).thenReturn(selected)
        assertEquals(21, buildJavaVersion(project(), session, manager))
    }

    @Test fun `compiler toolchain requirement uses same selected toolchain as compiler plugin`() {
        val session = session("17.0.12")
        val manager = mock(ToolchainManager::class.java)
        val project = project()
        project.build.addPlugin(Plugin().apply {
            groupId = "org.apache.maven.plugins"
            artifactId = "maven-compiler-plugin"
            configuration = node("configuration", node("jdkToolchain", node("version", "21")))
        })
        val selected = toolchain("compiler", "21.0.5")
        val context = toolchain("context", "11.0.25")
        `when`(manager.getToolchains(session, "jdk", mapOf("version" to "21"))).thenReturn(listOf(selected))
        `when`(manager.getToolchainFromBuildContext("jdk", session)).thenReturn(context)
        assertEquals(21, buildJavaVersion(project, session, manager))
    }

    @Test fun `compile execution configuration takes precedence over plugin toolchain default`() {
        val session = session("17.0.12")
        val manager = mock(ToolchainManager::class.java)
        val project = project()
        project.build.addPlugin(Plugin().apply {
            groupId = "org.apache.maven.plugins"
            artifactId = "maven-compiler-plugin"
            configuration = node("configuration", node("jdkToolchain", node("version", "11")))
            addExecution(PluginExecution().apply {
                id = "default-compile"
                configuration = node("configuration", node("jdkToolchain", node("version", "21")))
            })
        })
        val selected = toolchain("execution", "21.0.5")
        `when`(manager.getToolchains(session, "jdk", mapOf("version" to "21"))).thenReturn(listOf(selected))
        assertEquals(21, buildJavaVersion(project, session, manager))
    }

    @Test fun `explicit forked compiler reads its release metadata without executing javac`() {
        val session = session("17.0.12")
        val compiler = toolchain("executable", "21.0.5").findTool("javac")
        session.userProperties.setProperty("maven.compiler.executable", compiler)
        session.userProperties.setProperty("maven.compiler.fork", "true")
        assertEquals(21, buildJavaVersion(project(), session, mock(ToolchainManager::class.java)))
        session.userProperties.setProperty("maven.compiler.fork", "false")
        assertEquals(17, buildJavaVersion(project(), session, mock(ToolchainManager::class.java)))
    }

    @Test fun `missing compiler metadata cannot silently choose the analyzer JDK`() {
        val manager = mock(ToolchainManager::class.java)
        val missing = session("17")
        missing.systemProperties.remove("java.version")
        assertFailsWith<IllegalArgumentException> { buildJavaVersion(project(), missing, manager) }
        val selected = mock(Toolchain::class.java)
        `when`(manager.getToolchainFromBuildContext("jdk", missing)).thenReturn(selected)
        assertFailsWith<IllegalArgumentException> { buildJavaVersion(project(), missing, manager) }
    }

    @Test fun `malformed selected JDK release metadata is rejected`() {
        val session = session("17")
        val manager = mock(ToolchainManager::class.java)
        val selected = toolchain("broken", "0")
        `when`(manager.getToolchainFromBuildContext("jdk", session)).thenReturn(selected)
        assertFailsWith<IllegalArgumentException> { buildJavaVersion(project(), session, manager) }
        Files.writeString(temporary.resolve("broken/release"), "OTHER=17")
        assertFailsWith<IllegalArgumentException> { buildJavaVersion(project(), session, manager) }
        Files.writeString(temporary.resolve("broken/release"), "JAVA_VERSION=invalid")
        assertFailsWith<NumberFormatException> { buildJavaVersion(project(), session, manager) }
    }

    @Test fun `project compiler executable properties and compile goal configuration are honored`() {
        val session = session("17")
        val project = project().apply {
            properties.setProperty("maven.compiler.executable", toolchain("project", "21").findTool("javac"))
            properties.setProperty("maven.compiler.fork", "true")
            build.addPlugin(Plugin().apply {
                groupId = "unrelated"; artifactId = "plugin"
            })
            build.addPlugin(Plugin().apply {
                groupId = "org.apache.maven.plugins"; artifactId = "maven-compiler-plugin"
                addExecution(PluginExecution().apply { id = "testCompile"; addGoal("testCompile") })
                addExecution(PluginExecution().apply {
                    id = "custom"; addGoal("compile")
                    configuration = node("configuration", node("fork", "false"))
                })
            })
        }
        assertEquals(17, buildJavaVersion(project, session, mock(ToolchainManager::class.java)))
        project.build.plugins.last().executions.clear()
        assertEquals(21, buildJavaVersion(project, session, mock(ToolchainManager::class.java)))
    }

    @Test fun `unmatched compiler toolchain requirement falls back to the selected session toolchain`() {
        val session = session("17")
        val manager = mock(ToolchainManager::class.java)
        val project = project().apply {
            build.addPlugin(Plugin().apply {
                groupId = "org.apache.maven.plugins"; artifactId = "maven-compiler-plugin"
                configuration = node("configuration", node("jdkToolchain", node("version", "21")))
            })
        }
        `when`(manager.getToolchains(session, "jdk", mapOf("version" to "21"))).thenReturn(emptyList())
        val fallback = toolchain("fallback", "11")
        `when`(manager.getToolchainFromBuildContext("jdk", session)).thenReturn(fallback)
        assertEquals(11, buildJavaVersion(project, session, manager))
    }

    @Test fun `empty compiler setting and unavailable toolchain list use declared fallbacks`() {
        val session = session("17")
        val manager = mock(ToolchainManager::class.java)
        val project = project().apply {
            build.addPlugin(Plugin().apply {
                groupId = "org.apache.maven.plugins"; artifactId = "maven-compiler-plugin"
                configuration = node("configuration", node("jdkToolchain", node("version", "21"))).apply {
                    addChild(Xpp3Dom("executable"))
                }
            })
        }
        `when`(manager.getToolchains(session, "jdk", mapOf("version" to "21"))).thenReturn(null)
        assertEquals(17, buildJavaVersion(project, session, manager))
    }

    private fun session(version: String): MavenSession = mock(MavenSession::class.java).also {
        `when`(it.systemProperties).thenReturn(Properties().apply { setProperty("java.version", version) })
        `when`(it.userProperties).thenReturn(Properties())
    }

    private fun project() = MavenProject(Model().apply {
        build = org.apache.maven.model.Build()
    }).apply { file = temporary.resolve("pom.xml").toFile() }

    private fun toolchain(name: String, version: String): Toolchain {
        val home = Files.createDirectories(temporary.resolve(name))
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$version\"\n")
        val javac = Files.write(Files.createDirectories(home.resolve("bin")).resolve("javac"), byteArrayOf())
        return mock(Toolchain::class.java).also { `when`(it.findTool("javac")).thenReturn(javac.toString()) }
    }

    private fun node(name: String, value: String) = Xpp3Dom(name).apply { this.value = value }
    private fun node(name: String, child: Xpp3Dom) = Xpp3Dom(name).apply { addChild(child) }
}
