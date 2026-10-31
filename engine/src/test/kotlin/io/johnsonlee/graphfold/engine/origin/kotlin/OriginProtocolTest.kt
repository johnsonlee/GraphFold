package io.johnsonlee.graphfold.engine.origin.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol.KotlinFrontend
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OriginProtocolTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(KotlinFrontend::class)
    fun `typed frontend preserves compiler protocol wire labels`(frontend: KotlinFrontend) {
        KotlinOriginCollector(temporary, "2.1.20", frontend, "run").finish()
        val file = Files.list(temporary).use { it.toList().single() }
        val producer = jacksonObjectMapper().readTree(file.toFile())["producer"]
        assertEquals("kotlinc", producer["compiler"].asText())
        assertEquals("JVM", producer["backend"].asText())
        assertEquals(frontend.name, producer["frontend"].asText())
    }

    @Test
    fun `packaged origin vocabulary and javac provider load without Kotlin runtime`() {
        val jar = temporary.resolve("compiler-plugin.jar")
        requireNotNull(javaClass.getResourceAsStream("/engine/origin/compiler-plugin.jar")).use { Files.copy(it, jar) }
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("kotlin.jvm.internal.Intrinsics") }
            val protocol = OriginProtocol::class.java
            protocol.declaredClasses.forEach { type ->
                val isolated = loader.loadClass(type.name)
                val actual = isolated.enumConstants.map { (it as Enum<*>).name }
                assertEquals(type.enumConstants.map { (it as Enum<*>).name }, actual)
            }
            val compiler = loader.loadClass(OriginProtocol.Compiler::class.java.name)
            val wireValue = compiler.getMethod("wireValue")
            assertEquals(listOf("javac", "kotlinc"), compiler.enumConstants.map { wireValue.invoke(it) })
            assertTrue(loader.loadClass("io.johnsonlee.graphfold.engine.origin.javac.GraphFoldOrigins")
                .getDeclaredConstructor().newInstance() is com.sun.source.util.Plugin)
        }
    }
}
