package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Local call contracts must not depend on whether dependency-body propagation is requested. */
class SnapshotResourceCallTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source and dependency modes preserve modeled calls without blessing unknown receivers`(dependencies: Boolean) {
        val classes = compile("""
            public static void scalar() { System.out.println("text"); }
            public static void custom(java.io.PrintStream output) { output.println("text"); }
            public static void mixed(java.io.PrintStream output) {
                System.out.println("text");
                output.println("text");
            }
            public static String bundle() { return java.util.ResourceBundle.getBundle("messages").getString("key"); }
        """.trimIndent())
        val sink = SnapshotSink(dependencyResources = dependencies)
        val graph = JavaProjectLoader().loadObserved(classes, sink::capture).graph
        val calls = graph.nodes(CallSiteNode::class.java).filter { it.ordinal?.let { ordinal -> ordinal >= 0 } == true }.toList()
        fun sites(method: String, callee: String) = calls.filter { it.caller.name == method && it.callee.name == callee }
        val scalar = sites("scalar", "println").single()
        assertTrue(sink.modelsResourceCall(scalar))
        assertFalse(sink.modelsResourceCall(sites("custom", "println").single()))
        val mixed = sites("mixed", "println")
        assertEquals(2, mixed.size)
        assertTrue(mixed.none(sink::modelsResourceCall), "An unmodeled call must retain uncertainty for its shared signature")
        assertTrue(sink.modelsResourceCall(sites("bundle", "getString").single()))
        assertEquals(dependencies, sink.resourceInitializers.isNotEmpty())
        assertFalse(sink.modelsResourceCall(scalar.copy(ordinal = null)))
        assertFalse(sink.modelsResourceCall(scalar.copy(ordinal = -1)))
        assertFalse(sink.modelsResourceCall(scalar.copy(callee = scalar.callee.copy(name = "uncaptured"))))
        assertFalse(sink.modelsResourceCall(scalar.copy(caller = scalar.caller.copy(declaringClass = TypeDescriptor("Absent")))))
        sink.methods.remove(scalar.caller.identity)
        assertFalse(sink.modelsResourceCall(scalar), "A call model without its captured method is not proof")
    }

    @Test fun `ordinary capture requires verified Intrinsics before applying its resource contract`() {
        val library = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
        val classes = compile("public static void verify(Object value) { kotlin.jvm.internal.Intrinsics.checkNotNull(value); }", library)
        val declarations = ResourceDeclarations(listOf(library))
        assertTrue(declarations.trustedIntrinsics)
        val untrusted = SnapshotSink()
        val trusted = SnapshotSink(trustedIntrinsics = declarations.trustedIntrinsics)
        AnalysisInputs(classes, listOf(library)).use { inputs ->
            val graph = JavaProjectLoader(LoaderConfig(includePackages = listOf("Sample"), includeLibraries = true), false)
                .loadObserved(inputs.path) { observation -> untrusted.capture(observation); trusted.capture(observation) }.graph
            val site = graph.nodes(CallSiteNode::class.java).single {
                it.caller.name == "verify" && it.callee.name == "checkNotNull"
            }
            assertFalse(untrusted.modelsResourceCall(site))
            assertTrue(trusted.modelsResourceCall(site))
            assertEquals(setOf(site.callee.identity), untrusted.resourceCalls.getValue(site.caller.identity))
            assertTrue(trusted.resourceCalls.getValue(site.caller.identity).isEmpty())
        }
    }

    private fun compile(body: String, dependency: Path? = null): Path {
        val source = directory.resolve("Sample.java")
        Files.writeString(source, "public class Sample { $body }")
        val output = Files.createDirectories(directory.resolve("classes"))
        val arguments = mutableListOf("-g", "-d", output.toString())
        dependency?.let { arguments += listOf("-classpath", it.toString()) }
        arguments += source.toString()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return output
    }
}
