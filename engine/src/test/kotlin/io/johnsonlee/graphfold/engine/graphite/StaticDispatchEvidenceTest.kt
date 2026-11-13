package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.LeafApplicationDispatch

import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.origin.OriginCatalog
import io.johnsonlee.graphfold.engine.origin.OriginProject
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real frontend nodes verify the independent dispatch policy without inventing unresolved frontend gaps. */
class StaticDispatchEvidenceTest {
    @TempDir lateinit var directory: Path

    @Test fun `closed supplied classes and fixed declarations support independent dispatch evidence`() {
        val classes = compile()
        val analyzer = GraphiteAnalyzer()
        val sink = SnapshotSink()
        val raw = JavaProjectLoader(LoaderConfig(interproceduralDataflow = true), false).loadObserved(classes, sink::capture)
        val completeness = GraphiteCompleteness(raw.graph)
        assertTrue(completeness.diagnostics.isEmpty(), completeness.diagnostics.toString())
        assertTrue(completeness.methodDiagnostics.isEmpty(),
            "This concrete fixture is already resolved by the frontend; it does not prove Analyzer gap wiring")
        val application = setOf("Caller", "Caller\$Bean", "Caller\$FinalBean")
        val closed = LeafApplicationDispatch(classes, application)
        val runtimeSubclasses = LeafApplicationDispatch(classes, application, allowApplicationLeaf = false)
        for (callee in listOf(VIRTUAL, FINAL_METHOD, FINAL_OWNER)) {
            val site = raw.graph.nodes(CallSiteNode::class.java).single { it.callee.identity == callee }
            assertTrue(closed.proves(site, sink.methods), callee)
            assertEquals(callee != VIRTUAL, runtimeSubclasses.proves(site, sink.methods), callee)
        }
        val owners = listOf(emptyMap(), mapOf("Caller" to "Caller", "Caller\$Bean" to "Caller\$Bean",
            "Caller\$FinalBean" to "Caller\$FinalBean"))
        val ordinary = analyzer.analyze(classes, emptyList(), dependencyResources = true)
        assertFalse(hasGap(ordinary, VIRTUAL), ordinary.methodDiagnostics.toString())
        for (mapping in owners) {
            val project = OriginProject(StaticScope(classes, emptyList(), emptyList()), classes,
                OriginCatalog(emptyList(), emptyList()), mapping)
            val plain = analyzer.analyzeOriginProject(project, emptyList(), dependencyResources = true)
            assertTrue(plain.diagnostics.isEmpty(), plain.diagnostics.toString())
            assertFalse(hasGap(plain, VIRTUAL), "Plain closed classpath supports its leaf proof: $mapping")
            assertFalse(VIRTUAL in plain.externalEntries)
            assertEquals(mapping, plain.originalOwners)
            for (fixed in listOf(FINAL_METHOD, FINAL_OWNER)) {
                assertFalse(hasGap(plain, fixed), plain.methodDiagnostics.toString())
            }
        }
    }

    private fun hasGap(snapshot: ProgramSnapshot, callee: String): Boolean =
        snapshot.methodDiagnostics.values.flatten().any { it.dispatchOnly && it.callee == callee }

    private fun compile(): Path {
        val source = directory.resolve("Caller.java")
        Files.writeString(source, """
            public class Caller {
                public static String virtualCall(Bean bean) { return bean.value(); }
                public static String finalMethod(Bean bean) { return bean.fixed(); }
                public static String finalOwner(FinalBean bean) { return bean.value(); }
                public static class Bean {
                    public String value() { return "bean"; }
                    public final String fixed() { return "fixed"; }
                }
                public static final class FinalBean {
                    public String value() { return "final"; }
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    companion object {
        private const val VIRTUAL = "Caller\$Bean.value()Ljava/lang/String;"
        private const val FINAL_METHOD = "Caller\$Bean.fixed()Ljava/lang/String;"
        private const val FINAL_OWNER = "Caller\$FinalBean.value()Ljava/lang/String;"
    }
}
