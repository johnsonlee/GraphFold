package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldSites
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.Executors
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GraphiteAnalyzerTest {
    @TempDir lateinit var directory: Path

    private fun compile(name: String, body: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Sample.java")
        Files.writeString(source, """
            public class Sample {
                static boolean enabled(String key) { return true; }
                static void work() {}
                static void tail() {}
                $body
            }
        """.trimIndent())
        val output = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", output.toString(), source.toString()))
        return output
    }

    private fun rule(key: String = "checkout") = ConstantFold.parse(mapOf(
        "match" to mapOf("CallSite" to mapOf("callee_class" to "Sample", "callee_name" to "enabled")),
        "args" to mapOf(0 to key), "value" to false,
    ))

    @Test fun `public SPI captures folded method bodies and retains following work`() {
        val classes = compile("fold", "static void run() { if (enabled(\"checkout\")) work(); tail(); }")
        val plain = GraphiteAnalyzer().analyze(classes, emptyList())
        val folded = GraphiteAnalyzer().analyze(classes, listOf(rule()))
        val run = folded.methods.getValue("Sample.run()V")
        assertEquals(setOf("Sample.tail()V"), run.calls)
        assertTrue("Sample.work()V" in plain.methods.getValue("Sample.run()V").calls)
        assertEquals(listOf(1), folded.matchedRules)
        assertTrue(folded.diagnostics.isEmpty(), folded.diagnostics.toString())
        assertNotEquals(plain.methods.getValue("Sample.run()V").canonical, run.canonical)
        val again = GraphiteAnalyzer().analyze(classes, emptyList())
        assertEquals(plain.methods, again.methods, "fold capture must not leak into the next load")
        val baseline = compile("baseline", "static void run() { tail(); }")
        assertEquals(GraphiteAnalyzer().analyze(baseline, emptyList()).methods.getValue("Sample.run()V").canonical, run.canonical)
    }

    @Test fun `actual Cypher selects nodes with where and parameter constraints`() {
        val classes = compile("query", "static void run() { if (enabled(\"checkout\")) work(); if (enabled(\"other\")) tail(); }")
        val selected = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) WHERE cs.callee_name = 'enabled' RETURN cs",
            "args" to mapOf(0 to "checkout"), "value" to false,
        ))
        val audits = mutableMapOf<Int, RuleAuditUpdate>()
        val result = GraphiteAnalyzer { index, update -> audits[index] = update }.analyze(classes, listOf(selected))
        assertEquals(2, audits.getValue(0).selectedNodes.size)
        assertEquals(1, audits.getValue(0).matchedCallCount)
        assertEquals(setOf(0, 1), audits.getValue(0).selectedNodes.map { it.ordinal }.toSet())
        assertTrue(audits.getValue(0).selectedNodes.all { it.callee.signature == "Sample.enabled(java.lang.String)" })
        assertFalse("Sample.work()V" in result.methods.getValue("Sample.run()V").calls)
        assertTrue("Sample.tail()V" in result.methods.getValue("Sample.run()V").calls)
        assertTrue(result.diagnostics.any { it.contains("other") }, result.diagnostics.toString())
    }

    @Test fun `IR distinguishes arithmetic operands array indexing and exception handlers`() {
        val plus = compile("plus", "static int calc(int a, int b) { return a + b; }")
        val minus = compile("minus", "static int calc(int a, int b) { return a - b; }")
        val left = GraphiteAnalyzer().analyze(plus, emptyList()).methods.getValue("Sample.calc(II)I")
        val right = GraphiteAnalyzer().analyze(minus, emptyList()).methods.getValue("Sample.calc(II)I")
        assertNotEquals(left.canonical, right.canonical)
        assertTrue(left.canonical.contains(" + "), left.canonical)
        assertTrue(right.canonical.contains(" - "), right.canonical)
        val array = compile("array", "static int calc(int[] a, int b) { try { return a[b]; } catch (RuntimeException e) { return -1; } }")
        val body = GraphiteAnalyzer().analyze(array, emptyList()).methods.getValue("Sample.calc([II)I").canonical
        assertTrue(body.contains("java.lang.RuntimeException"), body)
        assertTrue(body.contains("[v"), body)
    }

    @Test fun `source whitespace and local variable spelling do not change canonical body`() {
        val a = compile("names1", "static int calc(int first) { int second = first + 1; return second; }")
        val b = compile("names2", "static int calc(int renamed) {\n int another = renamed + 1;\n return another; }")
        assertEquals(
            GraphiteAnalyzer().analyze(a, emptyList()).methods.getValue("Sample.calc(I)I").canonical,
            GraphiteAnalyzer().analyze(b, emptyList()).methods.getValue("Sample.calc(I)I").canonical,
        )
    }

    @Test fun `capture context is removed on failure and nesting is rejected`() {
        assertFailsWith<IllegalStateException> {
            SnapshotExtension.capture(SnapshotSink()) { error("failed load") }
        }
        SnapshotExtension.capture(SnapshotSink()) {
            assertFailsWith<IllegalStateException> { SnapshotExtension.capture(SnapshotSink()) {} }
        }
        SnapshotExtension.capture(SnapshotSink()) { }
    }

    @Test fun `invalid query shape fails instead of silently selecting nothing`() {
        val classes = compile("badquery", "static void run() { tail(); }")
        val selected = FoldSites.parse(mapOf("select" to "MATCH (cs:CallSite) RETURN cs.callee_name", "value" to false))
        assertFailsWith<IllegalStateException> { GraphiteAnalyzer().analyze(classes, listOf(selected)) }
    }

    @Test fun `jars preserve declaration metadata and abstract native method inventory`() {
        val classes = compile("metadata", "@Deprecated static final int VALUE = 7; native void nativeCall();")
        val jar = directory.resolve("sample.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("Sample.class"))
            zip.write(Files.readAllBytes(classes.resolve("Sample.class")))
            zip.closeEntry()
        }
        val snapshot = GraphiteAnalyzer().analyze(jar, emptyList())
        assertTrue(snapshot.diagnostics.single().contains("Native/JNI"), snapshot.diagnostics.toString())
        assertTrue(snapshot.methods.getValue("Sample.nativeCall()V").canonical.contains("NO_BODY"))
        assertTrue(snapshot.declarations.getValue("Sample").contains("Deprecated"))
        assertTrue(snapshot.declarations.getValue("Sample").contains("java.lang.Integer:1:7"))
    }

    @Test fun `rules matching nothing report zero and empty selections are accepted`() {
        val classes = compile("none", "static void run() { tail(); }")
        val select = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite {callee_name: 'missing'}) RETURN cs", "value" to false,
        ))
        val snapshot = GraphiteAnalyzer().analyze(classes, listOf(rule(), select))
        assertEquals(listOf(0, 0), snapshot.matchedRules)
        assertTrue(snapshot.diagnostics.isEmpty())
    }

    @Test fun `multiple select columns and other node types are rejected`() {
        val classes = compile("columns", "static void run() { tail(); }")
        listOf("MATCH (cs:CallSite) RETURN cs, cs.callee_name", "MATCH (n:Return) RETURN n").forEach { query ->
            val select = FoldSites.parse(mapOf("select" to query, "value" to false))
            assertFailsWith<IllegalArgumentException> { GraphiteAnalyzer().analyze(classes, listOf(select)) }
        }
    }

    @Test fun `nonconstant shared helper argument is retained and diagnosed`() {
        val classes = compile("shared", "static void run(String key) { if (enabled(key)) work(); }")
        val result = GraphiteAnalyzer().analyze(classes, listOf(rule()))
        assertTrue("Sample.work()V" in result.methods.getValue("Sample.run(Ljava/lang/String;)V").calls)
        assertTrue(result.diagnostics.any { it.contains("not a constant") }, result.diagnostics.toString())
    }

    @Test fun `receiver argument checks preserve the other experiment`() {
        val classes = compile("receiver", """
            static class Box { boolean on() { return true; } }
            static Box option(String key) { return new Box(); }
            static void run() { if (option("checkout").on()) work(); if (option("other").on()) tail(); }
        """.trimIndent())
        val selected = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs",
            "receiver_args" to mapOf(0 to "checkout"), "value" to false,
        ))
        val result = GraphiteAnalyzer().analyze(classes, listOf(selected))
        val calls = result.methods.getValue("Sample.run()V").calls
        assertFalse("Sample.work()V" in calls)
        assertTrue("Sample.tail()V" in calls)
        assertTrue(result.diagnostics.any { it.contains("other") })
        assertTrue(calls.any { it.contains("option") }, "producing the receiver is not silently erased")
    }

    @Test fun `concurrent analyses keep independent captures`() {
        val a = compile("thread1", "static int first() { return 11; }")
        val b = compile("thread2", "static int second() { return 22; }")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<ProgramSnapshot> { GraphiteAnalyzer().analyze(a, emptyList()) }
            val second = executor.submit<ProgramSnapshot> { GraphiteAnalyzer().analyze(b, emptyList()) }
            assertTrue("Sample.first()I" in first.get().methods)
            assertFalse("Sample.second()I" in first.get().methods)
            assertTrue("Sample.second()I" in second.get().methods)
            assertFalse("Sample.first()I" in second.get().methods)
        } finally { executor.shutdownNow() }
    }

    @Test fun `boolean functional invocation can fold through public query`() {
        val classes = compile("functional", """
            static void run() {
                java.util.function.BooleanSupplier supplier = () -> enabled("checkout");
                if (supplier.getAsBoolean()) work();
                tail();
            }
        """.trimIndent())
        val selected = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite {callee_name: 'getAsBoolean'}) WHERE cs.ordinal >= 0 RETURN cs",
            "value" to false,
        ))
        val result = GraphiteAnalyzer().analyze(classes, listOf(selected))
        assertFalse("Sample.work()V" in result.methods.getValue("Sample.run()V").calls)
        assertTrue("Sample.tail()V" in result.methods.getValue("Sample.run()V").calls)
    }

    @Test fun `derived boxed lambda calls resolve their bytecode origin and erased type`() {
        val classes = compile("boxed", """
            static void run() {
                java.util.function.Supplier<Boolean> supplier = () -> enabled("checkout");
                if (supplier.get()) work();
                tail();
            }
        """.trimIndent())
        val selected = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) WHERE cs.callee_name STARTS WITH 'lambda' RETURN cs",
            "value" to false,
        ))
        val result = GraphiteAnalyzer().analyze(classes, listOf(selected))
        assertFalse("Sample.work()V" in result.methods.getValue("Sample.run()V").calls)
        assertTrue("Sample.tail()V" in result.methods.getValue("Sample.run()V").calls)
        assertEquals(listOf(1), result.matchedRules)
    }

    @Test fun `reflection and private framework annotations block closed world assumptions`() {
        val classes = compile("reflect", """
            @Deprecated private void callback() { work(); }
            static void reflect() throws Exception { Sample.class.getDeclaredMethod("callback"); }
        """.trimIndent())
        val result = GraphiteAnalyzer().analyze(classes, emptyList())
        assertTrue(result.diagnostics.any { it.contains("reflective or dynamic") }, result.diagnostics.toString())
        assertTrue(result.diagnostics.any { it.contains("framework entry") }, result.diagnostics.toString())
    }

    @Test fun `external libraries resolve references without becoming application snapshots`() {
        val library = Files.createDirectories(directory.resolve("library"))
        val source = library.resolve("External.java")
        Files.writeString(source, "package lib; public class External { public static int value() { return 5; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", library.toString(), source.toString()))
        val jar = directory.resolve("dependency.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/External.class"))
            zip.write(Files.readAllBytes(library.resolve("lib/External.class")))
            zip.closeEntry()
        }
        val application = Files.createDirectories(directory.resolve("application"))
        val appSource = directory.resolve("App.java")
        Files.writeString(appSource, "public class App { public static int run() { return lib.External.value(); } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-cp", jar.toString(), "-d", application.toString(), appSource.toString(),
        ))
        val unresolved = GraphiteAnalyzer().analyze(application, emptyList())
        assertTrue(unresolved.diagnostics.any { it.contains("Unresolved class reference: lib.External") })
        val resolved = GraphiteAnalyzer().analyze(application, emptyList(), listOf(jar))
        assertTrue(resolved.diagnostics.isEmpty(), resolved.diagnostics.toString())
        assertTrue("lib.External.value()I" in resolved.methods.getValue("App.run()I").calls)
        assertFalse("lib.External.value()I" in resolved.methods)
        val directoryResolved = GraphiteAnalyzer().analyze(application, emptyList(), listOf(library))
        assertTrue(directoryResolved.diagnostics.isEmpty(), directoryResolved.diagnostics.toString())
        assertEquals(resolved.methods, directoryResolved.methods)
    }

    @Test fun `unread constructor scalar initializers erase without erasing following work`() {
        val before = compile("initializer-before", """
            private String label = "before";
            private Object optional = null;
            private int count = 1;
            Sample() { tail(); }
        """.trimIndent())
        val after = compile("initializer-after", """
            private String label = "after";
            private Object optional = null;
            private int count = 2;
            Sample() { tail(); }
        """.trimIndent())
        val left = GraphiteAnalyzer().analyze(before, emptyList()).methods.getValue("Sample.<init>()V")
        val right = GraphiteAnalyzer().analyze(after, emptyList()).methods.getValue("Sample.<init>()V")
        assertNotEquals(left.canonical, right.canonical)
        assertEquals(left.referenceCanonical, right.referenceCanonical)
        assertEquals(left.references, right.references)
        assertTrue("Sample.tail()V" in right.references)
    }

    @Test fun `writes on another constructor receiver cannot be erased as unused initialization`() {
        val before = compile("foreign-before", """
            private int count;
            Sample(Sample other) { other.count = 1; }
        """.trimIndent())
        val after = compile("foreign-after", """
            private int count;
            Sample(Sample other) { other.count = 2; }
        """.trimIndent())
        val identity = "Sample.<init>(LSample;)V"
        val left = GraphiteAnalyzer().analyze(before, emptyList()).methods.getValue(identity)
        val right = GraphiteAnalyzer().analyze(after, emptyList()).methods.getValue(identity)
        assertNotEquals(left.referenceCanonical, right.referenceCanonical)
        assertTrue("Sample.count:I" in right.references)
    }

    @Test fun `static writes outside initializer and initializer calls remain observable`() {
        val before = compile("stores-before", """
            private static int count;
            private int value = initialize();
            static int initialize() { work(); return 1; }
            static void run() { count = 1; }
        """.trimIndent())
        val after = compile("stores-after", """
            private static int count;
            private int value = initialize();
            static int initialize() { work(); return 1; }
            static void run() { count = 2; }
        """.trimIndent())
        val left = GraphiteAnalyzer().analyze(before, emptyList())
        val right = GraphiteAnalyzer().analyze(after, emptyList())
        assertNotEquals(left.methods.getValue("Sample.run()V").referenceCanonical,
            right.methods.getValue("Sample.run()V").referenceCanonical)
        val constructor = right.methods.getValue("Sample.<init>()V")
        assertTrue("Sample.initialize()I" in constructor.references)
        assertTrue("Sample.value:I" in constructor.references)
    }

    @Test fun `staged inputs clean up after success and reject nonjar dependencies`() {
        val classes = compile("staging", "static void run() { tail(); }")
        val file = directory.resolve("placeholder.jar")
        ZipOutputStream(Files.newOutputStream(file)).close()
        val stage: Path
        AnalysisInputs(classes, listOf(file)).use { inputs ->
            stage = inputs.path
            assertTrue(Files.exists(stage.resolve("Sample.class")))
            assertTrue(Files.exists(stage.resolve("dependency-0.jar")))
        }
        assertFalse(Files.exists(stage))
        val jarStage = AnalysisInputs(file, listOf(file)).use { inputs ->
            assertTrue(Files.isSymbolicLink(inputs.path.resolve("application.jar")))
            inputs.path
        }
        assertFalse(Files.exists(jarStage))
        assertFailsWith<IllegalArgumentException> { AnalysisInputs(classes, listOf(directory.resolve("missing.jar"))) }
    }

    @Test fun `inventory refuses duplicate and multi release declaration ambiguity`() {
        val classes = compile("inventory", "static void run() { tail(); }")
        val bytes = Files.readAllBytes(classes.resolve("Sample.class"))
        val jar = directory.resolve("ambiguous.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            listOf("Sample.class", "copy/Sample.class", "META-INF/versions/17/Sample.class", "lib/nested.jar", "docs/", "README.txt")
                .forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val inventory = BytecodeInventory().also { it.read(jar) }
        assertTrue(inventory.diagnostics.any { it.contains("Duplicate class") })
        assertTrue(inventory.diagnostics.any { it.contains("Multi-release") })
        assertTrue(inventory.diagnostics.any { it.contains("Nested archive") })
    }

}
