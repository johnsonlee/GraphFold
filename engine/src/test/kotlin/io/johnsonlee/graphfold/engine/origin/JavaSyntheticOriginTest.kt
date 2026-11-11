package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaSyntheticOriginTest {
    @TempDir lateinit var temporary: Path
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `javac switch holders require no allocation provenance but real anonymous classes still do`(missingRecord: Boolean) {
        val fixture = compileEnumSwitch()
        val holder = switchHolder(fixture.project)
        assertTrue(holder.innerClasses.any { it.name == holder.name && it.innerName == null })
        assertTrue(holder.access and Opcodes.ACC_SYNTHETIC != 0)
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        val records = assertIs<com.fasterxml.jackson.databind.node.ArrayNode>(document["records"])
        val omittedOwner = records[0]["owner"].asText()
        if (missingRecord) {
            records.remove(0)
            mapper.writeValue(file.toFile(), document)
        }
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.origins.none { it.owner == holder.name.replace('/', '.') })
        assertEquals(if (missingRecord) 1 else 2, result.origins.size)
        assertEquals(if (missingRecord) listOf("No verified compiler origin for anonymous class $omittedOwner")
            else emptyList(), result.diagnostics)
    }

    @Test
    fun `javac synthetic switch holder cannot substitute for a source anonymous allocation`() {
        val fixture = compileEnumSwitch()
        val file = manifest(fixture.project)
        val document = assertIs<ObjectNode>(mapper.readTree(file.toFile()))
        val owner = switchHolder(fixture.project).name.replace('/', '.')
        assertIs<ObjectNode>(document["records"][0]).put("owner", owner)
        mapper.writeValue(file.toFile(), document)
        val result = readOrigins(fixture.project, fixture.root)
        assertTrue(result.diagnostics.any { "not an anonymous class: $owner" in it }, result.diagnostics.toString())
        assertTrue(result.origins.none { it.owner == owner })
    }

    private fun switchHolder(project: CompiledProject): ClassNode = Files.walk(project.classes).use { files ->
        files.filter { it.toString().endsWith(".class") }.map { file ->
            ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        }.toList().single { node -> node.fields.any { it.name.startsWith("\$SwitchMap\$") } }
    }

    private fun compileEnumSwitch(): Fixture {
        val root = Files.createDirectories(temporary.resolve("repository"))
        val source = root.resolve("src/main/java/sample/Example.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, """
            package sample;
            public class Example {
                enum Mode { FIRST, SECOND }
                public int run(Mode mode) {
                    switch (mode) {
                        case FIRST: return 1;
                        default: return 2;
                    }
                }
                Object first = new Object() {};
                Object second = new Object() {};
            }
        """.trimIndent())
        return Fixture(root, ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("output")))
    }

    private fun manifest(project: CompiledProject): Path = Files.list(requireNotNull(project.originDirectory)).use {
        it.filter { file -> file.toString().endsWith(".json") }.toList().single()
    }

    private data class Fixture(val root: Path, val project: CompiledProject)
}
