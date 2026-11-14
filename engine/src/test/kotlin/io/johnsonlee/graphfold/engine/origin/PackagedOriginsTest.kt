package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.inputs.archive.CompilerEvidence
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler
import io.johnsonlee.graphfold.engine.inputs.archive.toStaticScope
import io.johnsonlee.graphfold.engine.inputs.archive.digest
import io.johnsonlee.graphfold.engine.inputs.archive.regularFiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedOriginsTest {
    @TempDir lateinit var temporary: Path
    private val mapper = jacksonObjectMapper()

    @Test fun `excluded anonymous outputs do not invalidate their complete compiler manifest`() {
        val compiler = compile("first")
        val packaged = pack(listOf(compiler), mapOf("sample/Example.class" to compiler.classes.resolve("sample/Example.class")))
        val catalog = readOrigins(packaged, temporary)
        assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
        assertTrue(catalog.origins.isEmpty())
        assertEquals(setOf("sample.Example"), catalog.compiledOwners)
    }

    @Test fun `independent compiler outputs with duplicate names project only matching packaged bytes`() {
        val first = compile("first")
        val second = compile("second")
        val packaged = pack(listOf(first, second), binaries(second))
        val catalog = readOrigins(packaged, temporary)
        assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
        assertEquals(listOf("second/src/main/java/Example.java"), catalog.origins.map { it.path })
        val project = OriginProject(packaged, packaged.classes, catalog, emptyMap())
        assertEquals(listOf(first.classes), project.sourceIndexClasses(temporary.resolve("first/src/main/java/Example.java")))
        assertEquals(listOf(second.classes), project.sourceIndexClasses(temporary.resolve("second/src/main/java/Example.java")))
        assertTrue(project.sourceIndexClasses(temporary.resolve("unowned/Example.java")).isEmpty())
    }

    @Test fun `packaging replacement relocation and ambiguous copies never inherit original provenance`() {
        val first = compile("first")
        val replacement = compile("replacement")
        val replaced = pack(listOf(first), binaries(replacement))
        assertTrue(readOrigins(replaced, temporary).diagnostics.any { "no byte-identical" in it })
        val relocated = pack(listOf(first), mapOf("moved/Example.class" to first.classes.resolve("sample/Example.class")))
        assertTrue(readOrigins(relocated, temporary).diagnostics.any { "no byte-identical" in it })
        val duplicate = Files.createDirectories(temporary.resolve("duplicate"))
        binaries(first).forEach { (name, source) ->
            val target = duplicate.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(source, target)
        }
        val evidence = first.compilerEvidence.single().copy(classes = duplicate)
        val ambiguous = pack(listOf(first.copy(compilerEvidence = first.compilerEvidence + evidence)), binaries(first))
        assertTrue(readOrigins(ambiguous, temporary).diagnostics.any { "ambiguous compiler outputs" in it })
    }

    @Test fun `excluded classes do not bypass stale original compiler evidence`() {
        val compiler = compile("first")
        val document = mapper.readTree(compiler.compilerEvidence.single().manifest.toFile())
        (requireNotNull(document.path("records").first()) as ObjectNode).put("classHash", "stale")
        mapper.writeValue(compiler.compilerEvidence.single().manifest.toFile(), document)
        val catalog = readOrigins(pack(listOf(compiler), emptyMap()), temporary)
        assertTrue(catalog.diagnostics.any { "class hash differs" in it })
    }

    private fun compile(name: String): StaticScope {
        val root = Files.createDirectories(temporary.resolve(name))
        Files.writeString(Files.createDirectories(root.resolve("src/main/java")).resolve("Example.java"), """
            package sample;
            public class Example {
                public static String id() { return "$name"; }
                public Object value() { return new Object() { public String toString() { return "$name"; } }; }
            }
        """.trimIndent())
        val project = SourceFixtureCompiler(Duration.ofMinutes(1)).compile(root, temporary.resolve("$name-output"))
            .toStaticScope(Files.createTempDirectory(temporary, "static-"))
        val manifest = regularFiles(requireNotNull(project.originDirectory)).single { it.toString().endsWith(".json") }
        val document = requireNotNull(mapper.readTree(manifest.toFile())) as ObjectNode
        document.put("runId", RUN_ID)
        document.path("records").forEach { record ->
            val binary = project.classes.resolve(record.path("owner").asText().replace('.', '/') + ".class")
            (requireNotNull(record) as ObjectNode).put("classHash", digest(binary))
        }
        mapper.writeValue(manifest.toFile(), document)
        return project.copy(originRunId = RUN_ID,
            requireOriginClassHashes = true, compilerEvidence = listOf(CompilerEvidence(project.classes, manifest)),
            sourceOutputs = mapOf(root to listOf(project.classes)))
    }

    private fun binaries(project: StaticScope): Map<String, Path> = regularFiles(project.classes)
        .filter { it.toString().endsWith(".class") }.associateBy { project.classes.relativize(it).toString() }

    private fun pack(compilers: List<StaticScope>, files: Map<String, Path>): StaticScope {
        val classes = Files.createTempDirectory(temporary, "packaged")
        files.forEach { (name, source) ->
            val target = classes.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(source, target)
        }
        return compilers.first().copy(classes = classes, compilerEvidence = compilers.flatMap { it.compilerEvidence },
            sourceOutputs = compilers.flatMap { it.sourceOutputs.entries }.associate { it.toPair() })
    }

    private companion object { const val RUN_ID = "maven-v3-packaged-test" }
}
