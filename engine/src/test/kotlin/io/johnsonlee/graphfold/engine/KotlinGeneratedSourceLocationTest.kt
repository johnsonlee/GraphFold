package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exact diff anchors remain required even when generated JVM members retain a CLASS proof obligation. */
class KotlinGeneratedSourceLocationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @TestFactory
    fun `generated Kotlin members retain exact source locations`(): List<DynamicTest> = CASES.map { case ->
        DynamicTest.dynamicTest(case.id) { verify(case) }
    }

    private fun verify(case: Case) {
        val repository = Files.createDirectory(temporary.resolve(case.id))
        val source = repository.resolve(SOURCE)
        Files.createDirectories(source.parent)
        val java = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(java.parent)
        Files.copy(fixtures.resolve("common/Ab.java"), java)
        val root = fixtures.resolve("source-locations/kotlin/${case.id}")
        val revisions = Git.init().setDirectory(repository.toFile()).call().use { git ->
            Files.copy(root.resolve("base/Example.kt"), source)
            val base = commit(git, "base")
            Files.copy(root.resolve("head/Example.kt"), source, StandardCopyOption.REPLACE_EXISTING)
            base to commit(git, "head")
        }
        val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(repository, temporary.resolve("${case.id}-bytecode"))
        verifyGeneratedStructure(case, readClasses(compiled.classes))
        if (case.structure == Structure.SUSPEND) verifyRealSuspension(compiled.classes)
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), revisions.first, revisions.second, Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertTrue(result.diagnostics.none { it.blocking }, "${case.id}: ${result.diagnostics}")
        val expected = listOf(Revision.BASE, Revision.HEAD).map { revision ->
            SourceLocation(revision, SOURCE, SourceRange(case.line, case.line), SourceRange(case.column, case.column + 1))
        }
        if (case.controlled) {
            assertEquals(Verdict.PASS, result.verdict, case.id)
            assertTrue(result.findings.isEmpty(), "${case.id}: ${result.findings}")
            assertEquals(expected.toSet(), result.reasons.flatMap { it.locations }.toSet(), case.id)
            assertTrue(result.reasons.any { it.code == ReasonCode.FOLDED_EQUIVALENT }, "${case.id}: ${result.reasons}")
        } else {
            assertEquals(Verdict.FAIL, result.verdict, case.id)
            assertTrue(result.findings.isNotEmpty(), case.id)
            assertEquals(expected.toSet(), result.findings.flatMap { it.locations }.toSet(), case.id)
            assertTrue(result.findings.all { it.locations.all(expected::contains) }, case.id)
        }
    }

    private fun commit(git: Git, message: String): String {
        git.add().addFilepattern(".").call()
        return git.commit().setMessage(message).setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }

    private fun readClasses(directory: Path): List<ClassNode> = Files.walk(directory).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.map { path ->
            ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
        }.toList()
    }

    private fun verifyGeneratedStructure(case: Case, classes: List<ClassNode>) {
        val example = classes.single { it.name == "sample/Example" }
        when (case.structure) {
            Structure.LAMBDA, Structure.CAPTURED_LAMBDA -> verifyLambda(case, example)
            Structure.DEFAULT -> {
                val generated = example.methods.single { it.name == "calculate\$default" }
                assertTrue(generated.access and Opcodes.ACC_SYNTHETIC != 0)
                assertTrue(generated.instructions.filterIsInstance<MethodInsnNode>().any { it.name == "calculate" })
            }
            Structure.ACCESSOR -> {
                val accessor = example.methods.single { it.name == "getValue" && it.desc == "()I" }
                assertFalse(accessor.access and Opcodes.ACC_SYNTHETIC != 0,
                    "A generated property accessor is not necessarily JVM synthetic")
            }
            Structure.BRIDGE -> {
                val bridge = example.methods.single { it.name == "map" && it.desc == "(Ljava/lang/Object;)Ljava/lang/Object;" }
                assertTrue(bridge.access and Opcodes.ACC_BRIDGE != 0)
                assertTrue(bridge.access and Opcodes.ACC_SYNTHETIC != 0)
            }
            Structure.SUSPEND -> verifySuspend(example, classes)
            Structure.INLINE -> {
                val run = example.methods.single { it.name == "run" }
                assertTrue(run.instructions.any { it.opcode == Opcodes.IADD }, "Inline body must appear at the call site")
                assertTrue(run.instructions.filterIsInstance<MethodInsnNode>().none { it.name == "calculate" })
                assertTrue(example.methods.any { it.name == "calculate" })
            }
        }
    }

    private fun verifyLambda(case: Case, example: ClassNode) {
        val invocation = example.methods.flatMap { it.instructions.filterIsInstance<InvokeDynamicInsnNode>() }
            .single { it.bsm.owner == "java/lang/invoke/LambdaMetafactory" }
        assertEquals(if (case.structure == Structure.CAPTURED_LAMBDA) 1 else 0, Type.getArgumentTypes(invocation.desc).size)
        val implementation = invocation.bsmArgs.filterIsInstance<Handle>().single()
        assertEquals(example.name, implementation.owner)
        assertEquals(Opcodes.H_INVOKESTATIC, implementation.tag)
        val method = example.methods.single { it.name == implementation.name && it.desc == implementation.desc }
        assertTrue(method.access and Opcodes.ACC_STATIC != 0)
        assertEquals(Type.getArgumentTypes(invocation.desc).toList(), Type.getArgumentTypes(method.desc).toList())
    }

    private fun verifySuspend(example: ClassNode, classes: List<ClassNode>) {
        assertTrue(example.methods.any { it.name == "run" && it.desc == "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;" })
        val stateMachine = classes.single { it.superName == "kotlin/coroutines/jvm/internal/ContinuationImpl" }
        assertTrue(stateMachine.fields.any { it.name == "label" && it.desc == "I" })
        assertTrue(stateMachine.methods.any { it.name == "invokeSuspend" })
        val run = example.methods.single { it.name == "run" }
        assertTrue(run.instructions.any { it.opcode == Opcodes.TABLESWITCH || it.opcode == Opcodes.LOOKUPSWITCH },
            "This case must contain a compiled suspension state machine, not just a Continuation parameter")
    }

    private fun verifyRealSuspension(classes: Path) {
        java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("sample.Example")
            val instance = type.getDeclaredConstructor().newInstance()
            val resumed = mutableListOf<Int>()
            val completion = object : kotlin.coroutines.Continuation<Int> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(result: Result<Int>) { resumed += result.getOrThrow() }
            }
            val returned = type.getMethod("run", kotlin.coroutines.Continuation::class.java).invoke(instance, completion)
            assertEquals(kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED, returned)
            assertTrue(resumed.isEmpty(), "Continuation must not complete before explicit resume")
            @Suppress("UNCHECKED_CAST")
            val pending = type.getMethod("getPending").invoke(instance) as kotlin.coroutines.Continuation<Int>
            pending.resumeWith(Result.success(10))
            assertEquals(listOf(12), resumed, "Head behavior must resume through the generated state machine")
        }
    }

    private enum class Structure { LAMBDA, CAPTURED_LAMBDA, DEFAULT, ACCESSOR, BRIDGE, SUSPEND, INLINE }
    private data class Case(val id: String, val line: Int, val column: Int, val structure: Structure, val controlled: Boolean = false)

    private companion object {
        const val SOURCE = "src/main/kotlin/sample/Example.kt"
        val CASES = listOf(
            Case("noncapturing_lambda", 5, 27, Structure.LAMBDA),
            Case("capturing_lambda", 5, 35, Structure.CAPTURED_LAMBDA),
            Case("default_parameter", 4, 32, Structure.DEFAULT),
            Case("property_accessor", 4, 28, Structure.ACCESSOR),
            Case("generic_bridge", 5, 58, Structure.BRIDGE),
            Case("suspend_state_machine", 9, 24, Structure.SUSPEND),
            Case("inline_body", 4, 53, Structure.INLINE),
            Case("controlled_lambda", 4, 54, Structure.LAMBDA, true),
            Case("controlled_default", 3, 59, Structure.DEFAULT, true),
            Case("controlled_accessor", 3, 55, Structure.ACCESSOR, true),
            Case("controlled_captured_lambda", 4, 62, Structure.CAPTURED_LAMBDA, true),
            Case("controlled_bridge", 4, 85, Structure.BRIDGE, true),
            Case("controlled_suspend", 8, 51, Structure.SUSPEND, true),
            Case("controlled_inline", 3, 80, Structure.INLINE, true),
        )
    }
}
