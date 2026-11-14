package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.AcceptanceDecision
import io.johnsonlee.graphfold.engine.WholeChangeSafety
import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticSafetyCallTest {
    @TempDir lateinit var temporary: Path
    private val summaries by lazy { compile() }

    @Test fun `resolved static calls preserve category two argument slots and return provenance`() {
        assertEquals(summary("identity", "(JID)I").semanticKey, summary("forward", "(JID)I").semanticKey)
        assertComplete(summary("forward", "(JID)I"))
        assertTrue(summary("forward", "(JID)I").dependencies.contains("CallCases.identity(JID)I"))
    }

    @Test fun `private final and superclass calls are resolved without trusting virtual names`() {
        listOf("privateCall", "finalCall", "superCall").forEach { assertComplete(summary(it, "()I")) }
        assertComplete(summary("finalOwnerCall", "(LFinalValue;)I"))
        val virtual = summary("virtualCall", "()I")
        assertEquals(ProofStatus.UNPROVEN, virtual.effectFree.status)
        assertNull(virtual.semanticKey)
        assertEquals(ProofStatus.UNPROVEN, summary("interfaceCall", "(LCallCases\$Value;)I").nonThrowing.status)
    }

    @Test fun `native abstract synchronized and missing dependency methods remain unproven`() {
        listOf("nativeCall", "synchronizedCall", "missingCall").forEach { name ->
            val result = summary(name, "()I")
            assertEquals(ProofStatus.UNPROVEN, result.effectFree.status, name)
            assertNull(result.semanticKey, name)
        }
        val abstractMethod = summaries.getValue("CallCases\$Value.value()I")
        assertEquals(ProofStatus.UNPROVEN, abstractMethod.terminating.status)
    }

    @Test fun `inspected cross owner initialization retains mutable and volatile field effects`() {
        assertComplete(summary("cleanStatic", "()I"))
        val mutableRead = summary("mutableRead", "()I")
        assertEquals(ProofStatus.PROVEN, mutableRead.nonThrowing.status)
        assertEquals(ProofStatus.PROVEN, mutableRead.effectFree.status)
        assertNull(mutableRead.semanticKey)
        listOf("volatileRead", "staticWrite", "instanceWrite").forEach { name ->
            assertEquals(ProofStatus.REFUTED, summary(name, "()I").effectFree.status, name)
        }
    }

    @Test fun `interface initializer closure and missing field inventories cannot certify safety`() {
        val initialized = summary("interfaceInitialization", "()I")
        assertEquals(ProofStatus.UNPROVEN, initialized.effectFree.status)
        assertNull(initialized.semanticKey)
        val field = summary("missingField", "()I")
        assertEquals(ProofStatus.UNPROVEN, field.nonThrowing.status)
        assertNull(field.semanticKey)
    }

    @Test fun `null receiver prevents field read or write while new allocation remains unproven`() {
        listOf("nullRead", "nullWrite").forEach { name ->
            assertEquals(ProofStatus.REFUTED, summary(name, "()I").nonThrowing.status, name)
        }
        assertEquals(ProofStatus.UNPROVEN, summary("newInitialized", "()I").effectFree.status)
        assertNull(summary("newInitialized", "()I").semanticKey)
    }

    @Test fun `deep nonrecursive call chain exceeds the bound without claiming recursion or safety`() {
        val source = temporary.resolve("Deep.java")
        val methods = (0..33).joinToString("\n") { index ->
            val expression = if (index == 33) "7" else "value${index + 1}()"
            "static int value$index() { return $expression; }"
        }
        Files.writeString(source, "class Deep { $methods }")
        val classes = Files.createDirectory(temporary.resolve("deep-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val results = StaticSafetyAnalysis(classes, emptyList()).use { it.summaries() }
        assertEquals(ProofStatus.UNPROVEN, results.getValue("Deep.value0()I").terminating.status)
        assertComplete(results.getValue("Deep.value33()I"))
    }

    @Test fun `field lookup requires both declared name and JVM descriptor`() {
        assertNotNull(summary("mutableRead", "()I"))
        StaticSafetyAnalysis(temporary.resolve("classes"), emptyList()).use { analysis ->
            assertNotNull(analysis.field("Clean", "field", "I"))
            assertNull(analysis.field("Clean", "field", "J"))
            assertNull(analysis.field("Clean", "absent", "I"))
        }
    }

    @Test fun `concrete allocation resolves its virtual target without claiming allocation purity`() {
        val result = summary("allocatedVirtual", "()I")
        assertEquals(ProofStatus.UNPROVEN, result.effectFree.status)
        assertEquals(ProofStatus.PROVEN, result.nonThrowing.status)
        assertEquals(ProofStatus.PROVEN, result.terminating.status)
        assertTrue("FinalValue.get()I" in result.dependencies)
        assertNull(result.semanticKey)
    }

    @Test fun `field access includes the failing class initializer before reading its value`() {
        val result = summary("failedInitializerRead", "()I")
        assertEquals(ProofStatus.REFUTED, result.nonThrowing.status)
        assertNull(result.semanticKey)
    }

    @Test fun `contradictory comparisons and switch cases cannot introduce a reachable null throw`() {
        listOf("contradictoryEqual", "contradictoryUnequal", "contradictorySwitch").forEach {
            assertComplete(summary(it, "(I)I"))
        }
    }

    @Test fun `hazards after unknown completion and computed selector paths remain unproven witnesses`() {
        val afterCall = summary("throwAfterUnknownCall", "(Ljava/lang/String;)I")
        val computedSwitch = summary("computedSwitch", "(I)I")
        listOf(afterCall, computedSwitch).forEach {
            assertEquals(ProofStatus.PROVEN, it.effectFree.status)
            assertEquals(ProofStatus.UNPROVEN, it.nonThrowing.status)
            assertEquals(ProofStatus.PROVEN, it.terminating.status)
            assertNull(it.semanticKey)
        }
    }

    @Test fun `resolved inherited bodies and initializer bodies remain explicit proof dependencies`() {
        val cases = mapOf(
            "inheritedStatic" to setOf("InheritedChild.value()I", "InheritedBase.value()I"),
            "inheritedFinal" to setOf("InheritedChild.finalValue()I", "InheritedBase.finalValue()I"),
            "initializedStatic" to setOf("Initialized.<clinit>()V", "InitializedBase.<clinit>()V"),
        )
        cases.forEach { (name, dependencies) ->
            val descriptor = if (name == "inheritedFinal") "(LInheritedChild;)I" else "()I"
            val result = summary(name, descriptor)
            assertComplete(result)
            dependencies.forEach { dependency ->
                assertTrue(dependency in result.dependencies, "$name must retain $dependency: ${result.dependencies}")
                val proof = WholeChangeSafety(
                    mapOf(result.identity to result), mapOf(result.identity to result))
                val assessment = proof.assess(listOf(result.identity), emptyList(), setOf(result.identity), setOf(dependency))
                assertEquals(AcceptanceDecision.REJECTED, assessment.decision, dependency)
            }
        }
    }

    private fun summary(name: String, descriptor: String) = summaries.getValue("CallCases.$name$descriptor")

    private fun assertComplete(summary: MethodSafetySummary) {
        assertEquals(ProofStatus.PROVEN, summary.effectFree.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.nonThrowing.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.terminating.status, summary.identity)
        assertNotNull(summary.semanticKey, summary.identity)
    }

    private fun compile(): Map<String, MethodSafetySummary> {
        val source = temporary.resolve("CallCases.java")
        Files.writeString(source, SOURCE)
        val classes = Files.createDirectory(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString()))
        Files.delete(classes.resolve("Missing.class"))
        return StaticSafetyAnalysis(classes, emptyList()).use { it.summaries() }
    }

    private companion object {
        val SOURCE = """
            final class FinalValue { int get() { return 7; } }
            class Base { int parent() { return 8; } }
            public class CallCases extends Base {
                int field;
                static long unused;
                static int identity(long wide, int value, double other) { return value; }
                static int forward(long wide, int value, double other) { return identity(wide, value, other); }
                private int privateValue() { return 7; }
                final int finalValue() { return 7; }
                int virtualValue() { return 7; }
                int privateCall() { return privateValue(); }
                int finalCall() { return finalValue(); }
                int virtualCall() { return virtualValue(); }
                int superCall() { return super.parent(); }
                static int finalOwnerCall(FinalValue value) { return value == null ? 0 : value.get(); }
                static int interfaceCall(Value value) { return value == null ? 0 : value.value(); }
                interface Value { int value(); }
                static native int nativeValue();
                static int nativeCall() { return nativeValue(); }
                static synchronized int synchronizedValue() { return 7; }
                static int synchronizedCall() { return synchronizedValue(); }
                static int missingCall() { return Missing.value(); }
                static int missingField() { return Missing.field; }
                static int allocatedVirtual() { return new FinalValue().get(); }
                static int failedInitializerRead() { return FailedInitializer.field; }
                static int contradictoryEqual(int x) {
                    if (x != 0) { if (x == 0) throw null; return 1; } return 0;
                }
                static int contradictoryUnequal(int x) {
                    if (x != 0) { if (x != 0) return 1; throw null; } return 0;
                }
                static int contradictorySwitch(int x) {
                    if (x == 1) return 1;
                    switch (x) { case 1: throw null; case 2: return 2; default: return 3; }
                }
                static int throwAfterUnknownCall(String value) { int length = value.length(); throw null; }
                static int computedSwitch(int x) { switch (x + 1) { case 0: throw null; default: return 2; } }
                static int inheritedStatic() { return InheritedChild.value(); }
                static int inheritedFinal(InheritedChild receiver) { return receiver == null ? 0 : receiver.finalValue(); }
                static int initializedStatic() { return Initialized.value(); }
                static int cleanStatic() { return Clean.value(); }
                static int mutableRead() { return Clean.field; }
                static int volatileRead() { return Clean.volatileField; }
                static int staticWrite() { Clean.field = 3; return 7; }
                int instanceWrite() { field = 3; return 7; }
                static int interfaceInitialization() { return HasInterface.read(); }
                static int nullRead() { CallCases value = null; return value.field; }
                static int nullWrite() { CallCases value = null; value.field = 3; return 7; }
                static int newInitialized() { return new Clean().instance; }
            }
            class Clean {
                int instance;
                Clean() { instance = 3; }
                static int field;
                static volatile int volatileField;
                static int value() { return 7; }
            }
            class HasInterface implements CallCases.Value {
                static int read() { return 7; }
                public int value() { return 7; }
            }
            class FailedInitializer { static int field = ((String)null).length(); }
            class InheritedBase { static int value() { return 7; } final int finalValue() { return 8; } }
            class InheritedChild extends InheritedBase { }
            class InitializedBase { static { int unused = 1; } }
            class Initialized extends InitializedBase { static { int unused = 2; } static int value() { return 9; } }
            class Missing { static int field; static int value() { return 7; } }
        """.trimIndent()
    }
}
