package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.sootup.GraphiteExtension
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotExtensionServiceTest {
    @Test
    fun `packaged engine discovers snapshot extension through generated SPI registration`() {
        val engineJar = File(requireNotNull(System.getProperty("graphfold.test.engineJar")))
        val service = GraphiteExtension::class.java.name
        val implementation = SnapshotExtension::class.java.name
        JarFile(engineJar).use { jar ->
            val descriptor = requireNotNull(jar.getJarEntry("META-INF/services/$service"))
            val providers = jar.getInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            assertEquals(implementation, providers)
        }

        // Exclude Gradle's classes/resource directories so a missing packaged registration cannot be masked.
        val dependencies = requireNotNull(System.getProperty("graphfold.test.runtimeClasspath"))
            .split(File.pathSeparator).map(::File).filter { it.isFile && it.extension == "jar" }
        val classpath = (listOf(engineJar) + dependencies).distinct().map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()).use { loader ->
            val serviceType = loader.loadClass(service)
            val provider = ServiceLoader.load(serviceType, loader).stream()
                .filter { it.type().name == implementation }.toList().single().get()
            assertTrue(serviceType.isInstance(provider))
            assertEquals(engineJar.canonicalFile, File(provider.javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile)
        }
    }
}
