package io.johnsonlee.graphfold.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.tools.ToolProvider
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaGeneratedSourceLocationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest(name = "{0}")
    @EnumSource(GeneratedCase::class)
    fun `generated Java behavior retains exact changed source coordinates`(case: GeneratedCase) {
        val fixtures = Path.of("../fixtures/source-locations/java", case.directory)
        val repository = Files.createDirectory(temporary.resolve("repository"))
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                copyTree(fixtures.resolve(revision), repository)
                verifyGeneratedBytecode(repository, case, revision)
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("Fixture", "fixture@example.invalid")
                    .setCommitter("Fixture", "fixture@example.invalid").call().name
            }
        }
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), commits[0], commits[1], Files.readString(Path.of("../fixtures/fold.yml"))),
        )
        assertTrue(result.diagnostics.none { it.blocking }, result.toString())
        assertEquals(if (case.controlled) Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        if (case.controlled) {
            assertTrue(result.findings.isEmpty(), result.toString())
        } else {
            val expected = listOf(Revision.BASE, Revision.HEAD).map { revision ->
                SourceLocation(revision, "src/main/java/sample/Example.java", SourceRange(case.line, case.line),
                    SourceRange(case.column, case.column + 1))
            }
            assertTrue(result.findings.isNotEmpty(), result.toString())
            assertTrue(result.findings.all { it.locations == expected }, result.toString())
            assertEquals(expected, result.findings.flatMap { it.locations }.distinct())
        }
    }

    private fun verifyGeneratedBytecode(repository: Path, case: GeneratedCase, revision: String) {
        val classes = Files.createDirectory(temporary.resolve("classes-$revision"))
        val source = repository.resolve("src/main/java/sample")
        val arguments = listOf("--release", "17", "-g", "-d", classes.toString(),
            source.resolve("Ab.java").toString(), source.resolve("Example.java").toString())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        val nodes = Files.walk(classes).use { files ->
            files.filter { it.toString().endsWith(".class") }.map { path ->
                ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
            }.toList()
        }
        val example = nodes.single { it.name == "sample/Example" }
        when (case.kind) {
            GeneratedKind.LAMBDA, GeneratedKind.CAPTURED_LAMBDA -> {
                assertTrue(example.methods.any { it.name.startsWith("lambda$") && it.access and Opcodes.ACC_SYNTHETIC != 0 })
                val lambda = example.methods.flatMap { it.instructions.toList().filterIsInstance<InvokeDynamicInsnNode>() }
                    .single { it.bsm.owner == "java/lang/invoke/LambdaMetafactory" }
                assertEquals(if (case.kind == GeneratedKind.CAPTURED_LAMBDA) 1 else 0, Type.getArgumentTypes(lambda.desc).size)
            }
            GeneratedKind.BRIDGE -> assertTrue(example.methods.any {
                it.name == "value" && it.desc == "()Ljava/lang/Object;" &&
                    it.access and Opcodes.ACC_BRIDGE != 0 && it.access and Opcodes.ACC_SYNTHETIC != 0
            })
            GeneratedKind.ANONYMOUS -> {
                val callback = nodes.single { it.name == "sample/Example$1" }
                assertEquals("sample/Example", callback.outerClass)
                assertEquals("run", callback.outerMethod)
                assertTrue(callback.methods.any { it.name == "applyAsInt" && it.desc == "(I)I" })
            }
        }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val destination = target.resolve(source.relativize(path))
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    enum class GeneratedKind { LAMBDA, CAPTURED_LAMBDA, BRIDGE, ANONYMOUS }

    enum class GeneratedCase(val directory: String, val kind: GeneratedKind, val line: Int, val column: Int, val controlled: Boolean) {
        LAMBDA("callback_lambda", GeneratedKind.LAMBDA, 4, 74, false),
        CAPTURED("callback_captured_lambda", GeneratedKind.CAPTURED_LAMBDA, 5, 83, false),
        ANONYMOUS("callback_anonymous", GeneratedKind.ANONYMOUS, 5, 63, false),
        BRIDGE("generic_bridge", GeneratedKind.BRIDGE, 5, 22, false),
        GATED_LAMBDA("gated_callback_lambda", GeneratedKind.LAMBDA, 5, 78, true),
        GATED_CAPTURED("gated_callback_captured_lambda", GeneratedKind.CAPTURED_LAMBDA, 6, 87, true),
        GATED_ANONYMOUS("gated_callback_anonymous", GeneratedKind.ANONYMOUS, 6, 67, true),
        GATED_BRIDGE("gated_generic_bridge", GeneratedKind.BRIDGE, 5, 49, true),
    }
}
