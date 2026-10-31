package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LineNumberNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real compiler identities must be observed before testing source-origin alignment. */
class AnonymousOriginIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @TestFactory
    fun `anonymous origins survive compiler numbering and retain exact diff anchors`(): List<DynamicTest> {
        val cases = jacksonObjectMapper().readValue<List<AnonymousFixtureCase>>(
            Files.readString(fixtures.resolve("anonymous-origins/manifest.json")),
        )
        return cases.map { case -> DynamicTest.dynamicTest("${case.language}/${case.id}") { verify(case) } }
    }

    @TestFactory
    fun `Kotlin physical encodings retain controlled verdicts and exact uncontrolled diff columns`(): List<DynamicTest> {
        val cases = jacksonObjectMapper().readValue<List<AnonymousFixtureCase>>(
            Files.readString(fixtures.resolve("anonymous-origins/manifest.json")),
        ).filter { it.language == "kotlin" && it.id in setOf("same_line", "same_line_controlled") }
        return cases.flatMap { case -> listOf(false, true).map { bom ->
            val encoding = if (bom) "BOM-CRLF" else "CRLF"
            DynamicTest.dynamicTest("${case.id}/$encoding") {
                verify(case, "-$encoding") { text -> (if (bom) "\uFEFF" else "") + text.replace("\n", "\r\n") }
            }
        } }
    }

    private fun verify(case: AnonymousFixtureCase, suffix: String = "", format: (String) -> String = { it }) {
        val directory = Files.createDirectory(temporary.resolve("${case.language}-${case.id}$suffix"))
        val repository = Files.createDirectory(directory.resolve("repository"))
        val extension = if (case.language == "java") "java" else "kt"
        val source = repository.resolve("src/main/${case.language}/sample/Example.$extension")
        Files.createDirectories(source.parent)
        val sdk = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(sdk.parent)
        Files.copy(fixtures.resolve("common/Ab.java"), sdk)
        val compiled = mutableListOf<List<ClassNode>>()
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                val text = Files.readString(fixtures.resolve("anonymous-origins/${case.language}/${case.id}/$revision/Example.$extension"))
                Files.writeString(source, format(text))
                val project = ProjectCompiler(Duration.ofMinutes(2)).compile(repository, directory.resolve(revision))
                compiled += readClasses(project.classes)
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("GraphFold Test", "test@example.invalid")
                    .setCommitter("GraphFold Test", "test@example.invalid").call().name
            }
        }
        verifyCompilerFacts(case, compiled)
        val result = GraphFoldEngine(EngineConfiguration(directory.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), commits[0], commits[1], Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertTrue(result.diagnostics.none { it.blocking }, "${case.id}: $result")
        assertEquals(case.expectedVerdict, result.verdict, "${case.id}: $result")
        val reported = if (case.expectedVerdict == Verdict.PASS) {
            assertTrue(result.findings.isEmpty(), result.toString())
            result.reasons.flatMap { it.locations }
        } else {
            assertTrue(result.findings.isNotEmpty(), result.toString())
            result.findings.flatMap { it.locations }
        }
        if (case.locations.isNotEmpty()) assertEquals(case.locations.toSet(), reported.toSet(), case.id)
        assertTrue(result.metadata.obligations > 0, "The change must be analyzed: $result")
    }

    private fun verifyCompilerFacts(case: AnonymousFixtureCase, revisions: List<List<ClassNode>>) {
        revisions.forEach { verifyCallbackStructure(case, it) }
        if (case.id.startsWith("super_argument")) verifySuperArgument(case, revisions)
        if (case.operation != "literal") {
            val owners = revisions.map(::liveCallbackOwner)
            if (case.anonymousOwnerDrift) assertNotEquals(owners[0], owners[1], "Compiler must renumber live callback: ${case.id}")
            else assertEquals(owners[0], owners[1], "Compiler retains method-scoped identity: ${case.id}")
        }
    }

    private fun verifyCallbackStructure(case: AnonymousFixtureCase, classes: List<ClassNode>) {
        val callbacks = classes.filter { type -> type.methods.any { it.name == "applyAsInt" && it.desc == "(I)I" } }
        assertTrue(callbacks.isNotEmpty(), case.id)
        if (case.id.startsWith("same_line") || case.id.startsWith("identical") || case.id.startsWith("nested")) {
            assertEquals(2, callbacks.size, "The fixture requires two real anonymous classes: ${case.id}")
        }
        if (case.id.startsWith("nested")) {
            assertTrue(callbacks.any { inner -> callbacks.any { outer -> inner.outerClass == outer.name } },
                "The inner callback must belong to an emitted anonymous class: ${case.id}")
        }
        val fileName = if (case.language == "java") "Example.java" else "Example.kt"
        assertTrue(callbacks.all { it.sourceFile == fileName })
        assertTrue(callbacks.all { type -> type.methods.filter { it.name == "applyAsInt" }.all { method ->
            method.instructions.any { it is LineNumberNode }
        } }, "Fixture must contain actual callback debug positions: ${case.id}")
    }

    private fun verifySuperArgument(case: AnonymousFixtureCase, revisions: List<List<ClassNode>>) {
        val anonymous = revisions.map(::anonymousBytecode)
        assertTrue(anonymous[0].isNotEmpty())
        if (case.language == "java") {
            assertEquals(anonymous[0], anonymous[1], "javac passes the changed argument from the caller")
        } else {
            assertNotEquals(anonymous[0], anonymous[1], "Kotlin lowers the constant superclass argument into the generated constructor")
            revisions.forEachIndexed { index, classes ->
                val type = classes.single { node -> node.innerClasses.any { it.name == node.name && it.innerName == null } }
                val constructor = type.methods.single { it.name == "<init>" }
                assertEquals("()V", constructor.desc)
                assertEquals(listOf(Opcodes.ALOAD, Opcodes.ICONST_1 + index, Opcodes.INVOKESPECIAL, Opcodes.RETURN),
                    constructor.instructions.map { it.opcode }.filter { it >= 0 })
            }
        }
    }

    private fun anonymousBytecode(classes: List<ClassNode>): Map<String, List<Byte>> = classes.filter { type ->
        type.innerClasses.any { it.name == type.name && it.innerName == null }
    }.associate { type ->
        // The per-compilation provenance annotation intentionally differs; executable class representation must not.
        val marker = "Lio/johnsonlee/graphfold/engine/origin/SourceOrigin;"
        type.visibleAnnotations = type.visibleAnnotations?.filterNot { it.desc == marker }?.toMutableList()
        type.invisibleAnnotations = type.invisibleAnnotations?.filterNot { it.desc == marker }?.toMutableList()
        val writer = ClassWriter(0)
        type.accept(writer)
        type.name to writer.toByteArray().toList()
    }

    private fun liveCallbackOwner(classes: List<ClassNode>): String = classes.single { type -> type.methods.any { method ->
        method.name == "applyAsInt" && method.instructions.any {
            it is IntInsnNode && it.opcode == Opcodes.BIPUSH && it.operand == 10
        }
    } }.name

    private fun readClasses(directory: Path): List<ClassNode> = Files.walk(directory).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.map { path ->
            ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
        }.toList()
    }

    private data class AnonymousFixtureCase(
        val id: String,
        val language: String,
        val expectedVerdict: Verdict,
        val operation: String,
        val anonymousOwnerDrift: Boolean,
        val locations: List<SourceLocation> = emptyList(),
    )
}
