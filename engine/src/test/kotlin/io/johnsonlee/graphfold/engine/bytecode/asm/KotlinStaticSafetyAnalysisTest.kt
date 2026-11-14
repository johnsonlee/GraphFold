package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Kotlin compiler output is analyzed directly; no fixture method is executed as evidence. */
class KotlinStaticSafetyAnalysisTest {
    @TempDir lateinit var temporary: Path
    private val summaries by lazy { compile() }

    @Test fun `ordinary Kotlin refactorings carry whole method proofs without a selected control`() {
        val plain = summary("identity", "(I)I")
        val refactored = summary("plusZero", "(I)I")
        assertComplete(refactored)
        assertEquals(plain.semanticKey, refactored.semanticKey)
        val changed = summary("zero", "(I)I")
        assertComplete(changed)
        assertNotEquals(plain.semanticKey, changed.semanticKey)
    }

    @Test fun `Kotlin null guards preserve nullable parameter and boxed value evidence`() {
        assertComplete(summary("guarded", "(Ljava/lang/String;)I"))
        assertComplete(summary("guardedBox", "(Ljava/lang/Integer;)I"))
        assertComplete(summary("shortCircuit", "(Ljava/lang/String;)Z"))
        assertEquals(ProofStatus.UNPROVEN, summary("unsafe", "(Ljava/lang/String;)I").nonThrowing.status)
        assertEquals(ProofStatus.UNPROVEN, summary("unbox", "(Ljava/lang/Integer;)I").nonThrowing.status)
        assertEquals(ProofStatus.REFUTED, summary("nullCrash", "()I").nonThrowing.status)
    }

    @Test fun `Kotlin compiler null checks require actual caller facts rather than annotations`() {
        assertEquals(ProofStatus.UNPROVEN, summary("helper", "(Ljava/lang/String;)I").nonThrowing.status)
        val caller = summary("safeCaller", "()I")
        assertComplete(caller)
        assertEquals(summary("two", "()I").semanticKey, caller.semanticKey)
        assertComplete(summary("nullableEquals", "(Ljava/lang/String;)Z"))
        assertComplete(summary("nullableReturnEquals", "()Z"))
        assertEquals(ProofStatus.REFUTED, summary("internalNull", "()I").nonThrowing.status)
    }

    @Test fun `Kotlin alternate branches cannot borrow a null guard from the safe path`() {
        val join = summary("unsafeJoin", "(Ljava/lang/String;Z)I")
        assertEquals(ProofStatus.UNPROVEN, join.nonThrowing.status)
        assertNull(join.semanticKey)
        assertComplete(summary("guardedJoin", "(Ljava/lang/String;Z)I"))
    }

    private fun summary(name: String, descriptor: String) = summaries.getValue("KotlinSafety.$name$descriptor")

    private fun assertComplete(summary: MethodSafetySummary) {
        assertEquals(ProofStatus.PROVEN, summary.effectFree.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.nonThrowing.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.terminating.status, summary.identity)
        assertNotNull(summary.semanticKey, summary.identity)
    }

    private fun compile(): Map<String, MethodSafetySummary> {
        val file = temporary.resolve("KotlinSafety.kt")
        Files.writeString(file, SOURCE)
        val classes = Files.createDirectory(temporary.resolve("classes"))
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-classpath", library.toString(),
                "-jvm-target", "17", "-d", classes.toString(), file.toString())
        }
        assertEquals(ExitCode.OK, result, output.toString())
        return StaticSafetyAnalysis(classes, listOf(library)).use { it.summaries() }
    }

    private companion object {
        val SOURCE = """
            class KotlinSafety {
                fun identity(x: Int): Int = x
                fun plusZero(x: Int): Int = x + 0
                fun zero(x: Int): Int = 0
                fun guarded(x: String?): Int { if (x == null) return 0; return x.length }
                fun guardedBox(x: Int?): Int { if (x == null) return 0; return x }
                fun shortCircuit(x: String?): Boolean = x != null && x.length > 0
                fun unsafe(x: String?): Int = x!!.length
                fun unbox(x: Int?): Int = x!!
                fun nullCrash(): Int { val x: String? = null; return x!!.length }
                private fun helper(x: String): Int = x.length
                fun safeCaller(): Int = helper("ok")
                fun two(): Int = 2
                fun nullableEquals(x: String?): Boolean = "Android".equals(x)
                private fun platform(): String? = null
                fun nullableReturnEquals(): Boolean = "Android".equals(platform())
                fun internalNull(): Int = platform()!!.length
                fun unsafeJoin(x: String?, b: Boolean): Int { val v = if(b) x else "ok"; return v!!.length }
                fun guardedJoin(x: String?, b: Boolean): Int {
                    if (x == null) return 0
                    val v = if(b) x else "ok"
                    return v.length
                }
            }
        """.trimIndent()
    }
}
