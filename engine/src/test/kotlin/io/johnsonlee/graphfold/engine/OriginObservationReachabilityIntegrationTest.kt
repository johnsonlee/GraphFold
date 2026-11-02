package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The folded graph determines whether an observer can expose an anonymous owner's changed runtime name. */
class OriginObservationReachabilityIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @TestFactory
    fun `runtime name observation is scoped to live folded paths`(): List<DynamicTest> =
        cases().map { case -> DynamicTest.dynamicTest("${case.language}/${case.id}") { verify(case) } }

    @TestFactory
    fun `the fixtures actually renumber a live callback with unchanged implementation`(): List<DynamicTest> =
        listOf("java", "kotlin").map { language -> DynamicTest.dynamicTest(language) {
            val case = ObservationCase("dead_stack_helper", language, Verdict.PASS)
            val directory = Files.createDirectory(temporary.resolve("compiler-$language"))
            val owners = listOf("base", "head").map { revision ->
                val repository = Files.createDirectory(directory.resolve(revision))
                writeSources(repository, case, revision)
                val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(repository, directory.resolve("$revision-output"))
                liveOwner(compiled.classes)
            }
            assertNotEquals(owners[0], owners[1], "The real compiler must renumber the callback in $language")
        } }

    @TestFactory
    fun `Java observation fixtures contain actual dynamic factories`(): List<DynamicTest> =
        cases().filter { it.language == "java" && (it.id.endsWith("concat") || it.id.endsWith("reference")) }
            .map { case -> DynamicTest.dynamicTest(case.id) {
                val directory = Files.createDirectory(temporary.resolve("dynamic-${case.id}"))
                val repository = Files.createDirectory(directory.resolve("repository"))
                writeSources(repository, case, "head")
                val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(repository, directory.resolve("output"))
                val factories = nodes(compiled.classes).flatMap { type -> type.methods.flatMap { method ->
                    method.instructions.filterIsInstance<InvokeDynamicInsnNode>()
                } }
                if (case.id.endsWith("concat")) {
                    assertTrue(factories.any { it.bsm.owner == "java/lang/invoke/StringConcatFactory" }, case.id)
                } else {
                    assertTrue(factories.any { call -> call.bsm.owner == "java/lang/invoke/LambdaMetafactory" &&
                        call.bsmArgs.filterIsInstance<Handle>().any { it.name == "toString" }
                    }, case.id)
                }
            } }

    private fun verify(case: ObservationCase) {
        val directory = Files.createDirectory(temporary.resolve("${case.language}-${case.id}"))
        val repository = Files.createDirectory(directory.resolve("repository"))
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                writeSources(repository, case, revision)
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("GraphFold Test", "test@example.invalid")
                    .setCommitter("GraphFold Test", "test@example.invalid").call().name
            }
        }
        val result = GraphFoldEngine(EngineConfiguration(directory.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), commits[0], commits[1], Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertEquals(case.expectedVerdict, result.verdict, "${case.language}/${case.id}: $result")
        assertTrue(result.metadata.obligations > 0, "The inserted expression must be analyzed: $result")
        if (case.expectedVerdict == Verdict.PASS) {
            assertTrue(result.diagnostics.none { it.blocking }, result.toString())
            assertTrue(result.findings.isEmpty(), result.toString())
            assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        } else {
            assertTrue("Runtime binary name" in result.toString(), "FAIL must identify the observation gap: $result")
            result.findings.flatMap { it.locations }.forEach { location ->
                assertEquals(Revision.HEAD, location.revision, result.toString())
                assertEquals(sourcePath(case), location.path, result.toString())
                assertEquals(5, location.line.start, result.toString())
                assertEquals(5, location.line.end, result.toString())
            }
        }
    }

    private fun writeSources(repository: Path, case: ObservationCase, revision: String) {
        val source = repository.resolve(sourcePath(case))
        Files.createDirectories(source.parent)
        Files.writeString(source, Files.readString(fixtures.resolve(
            "origin-observation/${case.language}/${case.id}/$revision/Example.${extension(case)}",
        )))
        val sdk = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(sdk.parent)
        Files.writeString(sdk, Files.readString(fixtures.resolve("common/Ab.java")))
    }

    private fun liveOwner(classes: Path): String = nodes(classes).single { node -> node.methods.any { method ->
        method.name == "get" && method.instructions.filterIsInstance<LdcInsnNode>().any { it.cst == "live" }
    } }.name

    private fun nodes(classes: Path): List<ClassNode> = Files.walk(classes).use { files ->
        files.filter { it.toString().endsWith(".class") }.map { path ->
            ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
        }.toList()
    }

    private fun sourcePath(case: ObservationCase): String = "src/main/${case.language}/sample/Example.${extension(case)}"

    private fun extension(case: ObservationCase): String = if (case.language == "java") "java" else "kt"

    private fun cases(): List<ObservationCase> = jacksonObjectMapper().readValue(
        Files.readString(fixtures.resolve("origin-observation/manifest.json")),
    )

    private data class ObservationCase(val id: String, val language: String, val expectedVerdict: Verdict)
}
