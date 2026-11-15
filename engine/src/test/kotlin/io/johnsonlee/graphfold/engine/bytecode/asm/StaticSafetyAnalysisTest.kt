package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures supply bytecode, never expected certificates or runtime observations. */
class StaticSafetyAnalysisTest {
    @TempDir lateinit var temporary: Path
    private val summaries by lazy { analyze(SOURCE) }

    @Test fun `whole method keys distinguish equivalent computations from pure changed results`() {
        val identity = summary("identity", "(I)I")
        listOf("plusZero", "dead", "helperCaller", "boxed").forEach { name ->
            val result = summary(name, "(I)I")
            assertComplete(result)
            assertEquals(identity.semanticKey, result.semanticKey, name)
        }
        val changed = summary("zero", "(I)I")
        assertComplete(changed)
        assertNotEquals(identity.semanticKey, changed.semanticKey)
    }

    @Test fun `null guards and short circuit edges prove dereferences with local facts`() {
        listOf("guarded" to "(Ljava/lang/String;)I", "shortCircuit" to "(Ljava/lang/String;)Z",
            "guardedJoin" to "(Ljava/lang/String;Z)I").forEach { (name, descriptor) -> assertComplete(summary(name, descriptor)) }
        assertEquals(ProofStatus.UNPROVEN, summary("unsafeJoin", "(Ljava/lang/String;Z)I").nonThrowing.status)
        assertEquals(ProofStatus.UNPROVEN, summary("unguarded", "(Ljava/lang/String;)I").nonThrowing.status)
        assertEquals(ProofStatus.REFUTED, summary("literalNull", "()I").nonThrowing.status)
    }

    @Test fun `literal equals permits nullable final argument but not hazardous argument evaluation`() {
        assertComplete(summary("equalsNullable", "(Ljava/lang/String;)Z"))
        assertEquals(ProofStatus.UNPROVEN, summary("equalsArgument", "(Ljava/lang/String;)Z").nonThrowing.status)
        assertEquals(ProofStatus.REFUTED, summary("internalNull", "()I").nonThrowing.status)
        assertEquals(ProofStatus.UNPROVEN, summary("unbox", "(Ljava/lang/Integer;)I").nonThrowing.status)
        assertEquals(ProofStatus.REFUTED, summary("unboxNull", "()I").nonThrowing.status)
    }

    @Test fun `callee facts include effects unsupported dispatch recursion and initialization`() {
        assertEquals(ProofStatus.REFUTED, summary("write", "(I)I").effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary("recursive", "(I)I").terminating.status)
        assertEquals(ProofStatus.UNPROVEN, summary("loop", "(I)I").terminating.status)
        assertEquals(ProofStatus.UNPROVEN, summary("unknown", "(Ljava/util/function/Supplier;)Ljava/lang/Object;").effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary("caught", "(Ljava/lang/String;)I").nonThrowing.status)
        assertEquals(ProofStatus.REFUTED, summary("initialized", "()I").nonThrowing.status)
        assertNull(summary("write", "(I)I").semanticKey)
    }

    @Test fun `stored getter result can be guarded but repeated mutable getters cannot share facts`() {
        val stored = summary("storedGetter", "(LSafetyExample\$Box;)I")
        assertEquals(ProofStatus.PROVEN, stored.nonThrowing.status)
        assertEquals(ProofStatus.PROVEN, stored.effectFree.status)
        assertNull(stored.semanticKey)
        val repeated = summary("repeatedGetter", "(LSafetyExample\$Box;)I")
        assertEquals(ProofStatus.UNPROVEN, repeated.nonThrowing.status)
        assertNull(repeated.semanticKey)
        assertEquals(ProofStatus.UNPROVEN, summary("allocation", "()Ljava/lang/Object;").effectFree.status)
    }

    @Test fun `all production methods include uninvoked hazardous callback bodies`() {
        val callback = summaries.values.single { it.identity.contains("lambda\$callback\$") }
        assertEquals(ProofStatus.REFUTED, callback.nonThrowing.status)
        assertEquals(ProofStatus.UNPROVEN, summary("callback", "()Ljava/lang/Runnable;").terminating.status)
        assertTrue(summaries.keys.any { it.contains("unusedHazard") })
        assertEquals(ProofStatus.REFUTED, summary("unusedHazard", "()I").nonThrowing.status)
    }

    @Test fun `guard joins do not leak one branch constants into caller summaries`() {
        val value = summary("joinedValue", "(I)I")
        assertComplete(value)
        assertEquals(summary("identity", "(I)I").semanticKey, value.semanticKey)
        assertNotEquals(summary("zero", "(I)I").semanticKey, summary("joinedCaller", "(I)I").semanticKey)
    }

    @Test fun `boxing retains primitive payload but never invents reference identity`() {
        val compared = summary("sameBoxes", "(I)Z")
        assertEquals(ProofStatus.PROVEN, compared.effectFree.status)
        assertEquals(ProofStatus.PROVEN, compared.nonThrowing.status)
        assertNull(compared.semanticKey)
        assertComplete(summary("sameAlias", "(I)Z"))
        assertEquals(summary("identity", "(I)I").semanticKey, summary("boxed", "(I)I").semanticKey)
    }

    @Test fun `unproved normal completion cannot establish a later effect witness`() {
        assertEquals(ProofStatus.UNPROVEN, summary("blockedEffect", "(I)I").effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary("blockedCalleeEffect", "(I)I").effectFree.status)
        assertEquals(ProofStatus.REFUTED, summary("guardedEffect", "(I)I").effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary("initializedEffect", "()I").effectFree.status)
        assertEquals(ProofStatus.UNPROVEN, summary("inheritedInitializationEffect", "()I").effectFree.status)
    }

    private fun summary(name: String, descriptor: String) = summaries.getValue("SafetyExample.$name$descriptor")

    private fun assertComplete(summary: MethodSafetySummary) {
        assertEquals(ProofStatus.PROVEN, summary.effectFree.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.nonThrowing.status, summary.identity)
        assertEquals(ProofStatus.PROVEN, summary.terminating.status, summary.identity)
        assertNotNull(summary.semanticKey, summary.identity)
    }

    private fun analyze(source: String): Map<String, MethodSafetySummary> {
        val file = temporary.resolve("SafetyExample.java")
        Files.writeString(file, source)
        val classes = Files.createDirectory(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()))
        return StaticSafetyAnalysis(classes, emptyList()).use { it.summaries() }
    }

    private companion object {
        val SOURCE = """
            public final class SafetyExample {
              static int count;
              public static int identity(int x) { return x; }
              public static int plusZero(int x) { return x + 0; }
              public static int dead(int x) { int unused = x + 1; return x; }
              private static int helper(int x) { return x + 0; }
              public static int helperCaller(int x) { return helper(x); }
              public static int boxed(int x) { return Integer.valueOf(x).intValue(); }
              public static boolean sameBoxes(int x) { return Integer.valueOf(x) == Integer.valueOf(x); }
              public static boolean sameAlias(int x) { Integer v = Integer.valueOf(x); return v == v; }
              public static int blockedEffect(int x) { int d=x-x; int v=10/d; count++; return v; }
              private static int uncertainCompletion(int x) { int d=x-x; return 10/d; }
              public static int blockedCalleeEffect(int x) { int v=uncertainCompletion(x); count++; return v; }
              public static int guardedEffect(int x) { if(x==0)return 0; int v=10/x; count++; return v; }
              public static int zero(int x) { return 0; }
              public static int guarded(String x) { if (x == null) return 0; return x.length(); }
              public static boolean shortCircuit(String x) { return x != null && x.length() > 0; }
              public static int guardedJoin(String x, boolean b) {
                if (x == null) return 0; String v; if (b) v=x; else v="ok"; return v.length();
              }
              public static int unsafeJoin(String x, boolean b) { String v; if (b) v=x; else v="ok"; return v.length(); }
              public static int unguarded(String x) { return x.length(); }
              public static int literalNull() { String x = null; return x.length(); }
              public static boolean equalsNullable(String x) { return "Android".equals(x); }
              private static String load(String x) { x.length(); return "Android"; }
              public static boolean equalsArgument(String x) { return "Android".equals(load(x)); }
              private static String getSafeNamedPlatform() { return null; }
              public static int internalNull() { return getSafeNamedPlatform().length(); }
              public static int unbox(Integer x) { return x; }
              public static int unboxNull() { Integer x = null; return x; }
              public static int write(int x) { count=x; return x; }
              public static int recursive(int x) { return recursive(x); }
              public static int loop(int x) { while (x>0) x--; return x; }
              public static Object unknown(java.util.function.Supplier<?> supplier) { return supplier.get(); }
              public static int caught(String x) { try { return x.length(); } catch (Exception e) { return 0; } }
              static final class Init {
                static int n=fail(); private static int fail() { return 1 / 0; } static int value() { return 1; }
                static int effect() { count++; return 1; }
              }
              static class Parent { static int n=fail(); static int fail() { return 1 / 0; } }
              static final class Child extends Parent { static { count++; } static int value() { return 1; } }
              public static int initialized() { return Init.value(); }
              public static int initializedEffect() { return Init.effect(); }
              public static int inheritedInitializationEffect() { return Child.value(); }
              public static final class Box { String value; public String get() { return value; } }
              public static int storedGetter(Box box) {
                if(box==null) return 0; String v=box.get(); if(v==null)return 0; return v.length();
              }
              public static int repeatedGetter(Box box) {
                if(box==null)return 0; if(box.get()==null)return 0; return box.get().length();
              }
              public static Object allocation() { return new Object(); }
              public static Runnable callback() { return () -> { String x=null; x.length(); }; }
              private static int unusedHazard() { String x=null; return x.length(); }
              private static int joined(int x) { if(x==0)return x; return x; }
              public static int joinedValue(int x) { return joined(x); }
              public static int joinedCaller(int x) { return joined(x)+1; }
            }
        """.trimIndent()
    }
}
