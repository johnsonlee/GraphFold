package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.ResourceDeclarations
import io.johnsonlee.graphfold.engine.bytecode.sootup.canonicalBody

import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.input.BodyInterceptionEvidence
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.JvmMethodIdentity
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.MethodFoldObservation
import io.johnsonlee.graphite.sootup.FoldedBodySnapshot
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.sootup.MethodObservationEvidence
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import sootup.core.model.MethodModifier

class SnapshotCaptureTest {
    @TempDir lateinit var directory: Path

    @Test fun `observed dependency bodies preserve folded resource effects without becoming application classes`() {
        val library = compile("library", "Reader", """
            package external;
            public abstract class Reader {
                public static boolean enabled() { return true; }
                public static String read() {
                    if (enabled()) return java.util.ResourceBundle.getBundle("messages").getString("new");
                    return java.util.ResourceBundle.getBundle("messages").getString("old");
                }
                public abstract void abstractRead();
                public native void nativeRead();
            }
        """.trimIndent())
        val application = compile("application", "App", """
            public class App { public static String run() { return external.Reader.read(); } }
        """.trimIndent(), library)
        val declarations = ResourceDeclarations(listOf(application, library))
        val sink = SnapshotSink(applicationOwners = declarations.classes, dependencyResources = true)
        val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
            "callee_class" to "external.Reader", "callee_name" to "enabled")), "value" to false))
        AnalysisInputs(application, listOf(library)).use { inputs ->
            val loaded = JavaProjectLoader(LoaderConfig(
                includeLibraries = true, includePackages = listOf("App"),
                dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("*")),
                folding = FoldPlan(listOf(fold)), interproceduralDataflow = true,
            ), false).loadObserved(inputs.path, sink::capture)
            val identity = "external.Reader.read()Ljava/lang/String;"
            assertEquals(listOf(ResourceRead("messages", "old")), sink.methods.getValue(identity).resourceReads)
            assertFalse(sink.methods.getValue(identity).calls.any { ".enabled(" in it })
            val receipt = sink.observations.getValue(identity)
            assertEquals(false, receipt.application)
            assertEquals(ArtifactRole.LIBRARY, receipt.role)
            assertEquals(loaded.completeness.classes.getValue("external.Reader").origin, receipt.origin)
            assertEquals(AnalysisStatus.ANALYZED, receipt.status)
            assertEquals(mapOf(0 to 1), receipt.folding?.foldedCalls)
            assertEquals(1, loaded.foldReport?.outcomes?.single()?.matched)
            assertTrue(sink.observations.getValue("App.run()Ljava/lang/String;").application == true)
            assertEquals(AnalysisStatus.NATIVE, sink.observations.getValue("external.Reader.nativeRead()V").status)
            assertEquals(AnalysisStatus.ABSTRACT, sink.observations.getValue("external.Reader.abstractRead()V").status)
            assertTrue(sink.methods.getValue("external.Reader.nativeRead()V").canonical.endsWith("\nNO_BODY"))
            assertTrue(sink.resourceInitializers.getValue(identity).contains("java.util.ResourceBundle"))
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["RESOLUTION_FAILED", "INCOMPLETE", "EXCLUDED_BY_SCOPE", "NOT_EXPANDED"])
    fun `failed or unexpanded observations retain receipts without successful method facts`(status: AnalysisStatus) {
        val sink = SnapshotSink(dependencyResources = true)
        val observation = unavailable(status)
        sink.capture(observation)
        assertSame(observation.evidence, sink.observations.getValue("Reader.read()V"))
        assertTrue(sink.methods.isEmpty())
        assertTrue(sink.resourceCalls.isEmpty())
        assertTrue(sink.resourceInitializers.isEmpty())
    }

    @Test fun `unresolved modifiers are not replaced with an empty successful declaration`() {
        val sink = SnapshotSink()
        sink.capture(unavailable(AnalysisStatus.RESOLUTION_FAILED, null))
        assertEquals(setOf("Reader.read()V"), sink.observations.keys)
        assertTrue(sink.methods.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["ANALYZED", "ANALYZED_WITHOUT_CONTROL_FLOW"])
    fun `an allegedly analyzed observation with no body cannot become an empty successful method`(status: AnalysisStatus) {
        val sink = SnapshotSink()
        sink.capture(unavailable(status))
        assertEquals(status, sink.observations.getValue("Reader.read()V").status)
        assertTrue(sink.methods.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["ABSTRACT", "NATIVE", "NO_BODY"])
    fun `explicit no body declarations retain their identity and typed receipt`(status: AnalysisStatus) {
        val sink = SnapshotSink(dependencyResources = true)
        sink.capture(unavailable(status))
        val method = sink.methods.getValue("Reader.read()V")
        assertEquals("PUBLIC\nNO_BODY", method.canonical)
        assertTrue(method.calls.isEmpty())
        assertTrue(method.lines.isEmpty())
        assertEquals(status, sink.observations.getValue(method.identity).status)
        assertEquals(emptySet(), sink.resourceCalls.getValue(method.identity))
        assertEquals(emptySet(), sink.resourceInitializers.getValue(method.identity))
    }

    @Test fun `duplicate observation fails without overwriting the first receipt`() {
        val sink = SnapshotSink()
        val first = unavailable(AnalysisStatus.ABSTRACT)
        sink.capture(first)
        assertFailsWith<IllegalStateException> { sink.capture(unavailable(AnalysisStatus.NATIVE)) }
        assertSame(first.evidence, sink.observations.getValue("Reader.read()V"))
    }

    @Test fun `observer failure does not contaminate a subsequent independent capture`() {
        val classes = compile("failure", "App", "public class App { public static int value() { return 7; } }")
        val loader = JavaProjectLoader()
        assertFailsWith<IllegalStateException> { loader.loadObserved(classes) { error("consumer failed") } }
        val sink = SnapshotSink()
        loader.loadObserved(classes, sink::capture)
        assertEquals(setOf("App.<init>()V", "App.value()I"), sink.methods.keys)
        assertTrue(sink.methods.getValue("App.value()I").canonical.contains("7"))
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["ABSTRACT", "NATIVE", "NO_BODY"])
    fun `a no body receipt with a concrete snapshot cannot produce successful method facts`(status: AnalysisStatus) {
        val classes = compile("inconsistent", "App", "public class App { public static int value() { return 7; } }")
        val sink = SnapshotSink()
        JavaProjectLoader().loadObserved(classes) { observed ->
            if (observed.evidence.method.name == "value") {
                sink.capture(object : FoldedMethodObservation {
                    override val snapshot = observed.snapshot
                    override val evidence = object : MethodObservationEvidence by observed.evidence {
                        override val status = status
                    }
                })
            }
        }
        assertEquals(status, sink.observations.getValue("App.value()I").status)
        assertTrue(sink.methods.isEmpty())
        assertTrue(sink.resourceCalls.isEmpty())
    }

    @Test fun `constant initializer stores remain production changes even without field readers`() {
        val classes = compile("stores", "App", """
            public class App {
                private int own;
                private static int shared;
                static { shared = 1; Other.value = 2; }
                public App(App other) { own = 1; other.own = 2; shared = 3; }
                public void update() { own = 4; shared = 5; }
            }
            class Other { static int value; }
        """.trimIndent())
        val references = mutableMapOf<String, List<String>>()
        JavaProjectLoader().loadObserved(classes) { observed ->
            val body = observed.snapshot?.body
            if (body != null) {
                val found = mutableListOf<String>()
                canonicalBody(body, found)
                references[observed.evidence.method.identity] = found
            }
        }
        assertEquals(2, references.getValue("App.<init>(LApp;)V").count { it == "App.own:I" })
        assertTrue("App.shared:I" in references.getValue("App.<init>(LApp;)V"))
        assertTrue("App.shared:I" in references.getValue("App.<clinit>()V"))
        assertTrue("Other.value:I" in references.getValue("App.<clinit>()V"))
        assertEquals(setOf("App.own:I", "App.shared:I"), references.getValue("App.update()V").toSet())
    }

    @Test fun `an initializer store with lost control flow evidence is never erased`() {
        val classes = compile("disconnected", "App", "public class App { private static int value = 1; }")
        var captured = false
        JavaProjectLoader().loadObserved(classes) { observed ->
            if (observed.evidence.method.name == "<clinit>") {
                val builder = sootup.core.model.Body.builder(requireNotNull(observed.snapshot).body,
                    requireNotNull(observed.evidence.modifiers))
                val body = builder.build()
                val store = body.stmts.single { it.isJAssignStmt &&
                    it.asJAssignStmt().leftOp is sootup.core.jimple.common.ref.JStaticFieldRef }
                val successor = builder.controlFlowGraph.successors(store).single()
                // SootUp exposes an unmodifiable view, not an independent graph copy. Simulate
                // incomplete producer evidence without relaxing the builder's normal validation.
                builder.controlFlowGraph.removeEdge(store, successor)
                assertTrue(body.controlFlowGraph.successors(store).isEmpty())
                val references = mutableListOf<String>()
                canonicalBody(body, references)
                assertTrue("App.value:I" in references)
                captured = true
            }
        }
        assertTrue(captured)
    }

    @Test fun `unused fields still retain nonconstant constructor and static initializer stores`() {
        val classes = compile("nonconstant-stores", "App", """
            public class App {
                private int value;
                private static int counter = initialize();
                public App(int input) { value = input; }
                private static int initialize() { System.out.println("initialize"); return 1; }
            }
        """.trimIndent())
        val references = mutableMapOf<String, List<String>>()
        JavaProjectLoader().loadObserved(classes) { observed ->
            val body = observed.snapshot?.body
            if (body != null) {
                val found = mutableListOf<String>()
                canonicalBody(body, found)
                references[observed.evidence.method.identity] = found
            }
        }
        assertTrue("App.value:I" in references.getValue("App.<init>(I)V"))
        assertTrue("App.counter:I" in references.getValue("App.<clinit>()V"))
        assertTrue("App.initialize()I" in references.getValue("App.<clinit>()V"))
    }

    private fun compile(name: String, type: String, source: String, dependency: Path? = null): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val file = root.resolve("$type.java")
        Files.writeString(file, source)
        val output = Files.createDirectories(root.resolve("classes"))
        val arguments = mutableListOf("-g", "-d", output.toString())
        dependency?.let { arguments += listOf("-classpath", it.toString()) }
        arguments += file.toString()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return output
    }

    private fun unavailable(
        status: AnalysisStatus, modifiers: Set<MethodModifier>? = setOf(MethodModifier.PUBLIC),
    ): FoldedMethodObservation = object : FoldedMethodObservation {
        override val snapshot: FoldedBodySnapshot? = null
        override val evidence = object : MethodObservationEvidence {
            override val method = JvmMethodIdentity("Reader", "read", "()V")
            override val modifiers = modifiers
            override val origin = "library.jar"
            override val role = ArtifactRole.LIBRARY
            override val application = false
            override val status = status
            override val message: String? = null
            override val recovery: BodyInterceptionEvidence? = null
            override val folding: MethodFoldObservation? = null
        }
    }
}
