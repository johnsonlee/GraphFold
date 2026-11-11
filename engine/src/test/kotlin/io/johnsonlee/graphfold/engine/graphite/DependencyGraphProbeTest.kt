package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DependencyGraphProbeTest {
    @TempDir lateinit var directory: Path

    @Test fun `expanded graph exposes stdlib dependency bodies and resource calls after folding`() {
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val dependency = compileDependency()
        val application = compileApplication(library, dependency)
        val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf(
            "callee_class" to "Probe", "callee_name" to "enabled")), "value" to false))
        val sink = SnapshotSink(applicationOwners = setOf("Probe", "external.Reader", "external.Reader\$Callback"))
        val messages = mutableListOf<String>()
        AnalysisInputs(application, listOf(dependency, library)).use { inputs ->
            val loader = JavaProjectLoader(LoaderConfig(
                includeLibraries = true, includePackages = listOf("Probe"), interproceduralDataflow = true,
                dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("*")),
                folding = FoldPlan(listOf(fold)), verbose = messages::add,
            ), false)
            val graph = loader.loadObserved(inputs.path, sink::capture).graph
            val calls = graph.nodes(CallSiteNode::class.java).toList()
            val parameters = graph.nodes(ParameterNode::class.java).toList()
            val returns = graph.nodes(ReturnNode::class.java).toList()
            val parameterEdges = parameters.filter { it.method.declaringClass.className == "external.Reader" }
                .flatMap { graph.incoming(it.id).toList() }.filterIsInstance<DataFlowEdge>()
                .filter { it.kind == DataFlowKind.PARAMETER_PASS }
            val returnEdges = returns.filter { it.method.declaringClass.className == "external.Reader" }
                .flatMap { graph.outgoing(it.id).toList() }.filterIsInstance<DataFlowEdge>()
                .filter { it.kind == DataFlowKind.RETURN_VALUE }
            assertTrue(sink.methods.keys.any { it.startsWith("kotlin.text.StringsKt__StringsKt.") })
            val libraryObservation = sink.observations.getValue("external.Reader.hardcoded()Ljava/lang/String;")
            assertEquals(ArtifactRole.LIBRARY, libraryObservation.role)
            assertEquals(false, libraryObservation.application)
            assertTrue(sink.methods.getValue("external.Reader.hardcoded()Ljava/lang/String;").resourceReads
                .contains(ResourceRead("messages", "unused")))
            assertTrue(sink.methods.getValue("external.Reader.dynamic(Ljava/lang/String;)Ljava/lang/String;").resourceReads
                .contains(ResourceRead(null, "unused")))
            assertTrue(sink.methods.getValue("Probe.run(Ljava/lang/String;)Ljava/lang/String;").calls
                .none { it.startsWith("external.Reader.hardcoded") })
            assertTrue(parameterEdges.isNotEmpty())
            assertTrue(returnEdges.isNotEmpty())
            println("PROBE stdlib=$library methods=${sink.methods.size} calls=${calls.size} " +
                "dependencyParameterEdges=${parameterEdges.size} dependencyReturnEdges=${returnEdges.size} " +
                "bodyRecovery=${loader.bodyInterceptionFallbackCount}")
            val callback = calls.single { it.caller.signature.contains("Reader.callback") &&
                it.callee.name == "callback\$lambda\$0" }
            assertTrue(callback.origin != null)
            assertTrue(sink.methods.getValue("Probe.callback\$lambda\$0()Ljava/lang/String;").resourceReads
                .contains(ResourceRead("messages", "unused")))
            val trim = sink.methods.getValue("kotlin.text.StringsKt__StringsKt.trim(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;")
            println("PROBE trim calls=${trim.calls} reads=${trim.resourceReads}")
            println("PROBE application calls=" + calls.filter { it.caller.declaringClass.className == "Probe" }
                .map { "${it.caller.identity} -> ${it.callee.identity}" })
            println("PROBE callback calls=" + calls.filter { it.caller.signature.contains("Reader.callback") }
                .map { "${it.caller.identity} -> ${it.callee.identity}; derived=${it.origin != null}" })
            println("PROBE incomplete=" + messages.filter(::isGraphiteIncompleteDiagnostic))
        }
    }

    private fun compileDependency(): Path {
        val source = directory.resolve("Reader.java")
        Files.writeString(source, """
            package external;
            public class Reader {
                public interface Callback { String get(); }
                public static String hardcoded() { return java.util.ResourceBundle.getBundle("messages").getString("unused"); }
                public static String dynamic(String name) { return java.util.ResourceBundle.getBundle(name).getString("unused"); }
                public static String callback(Callback callback) { return callback.get(); }
                public static String identity(String text) { return text; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("dependency"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun compileApplication(library: Path, dependency: Path): Path {
        val source = directory.resolve("Probe.kt")
        Files.writeString(source, """
            class Probe {
                fun enabled() = true
                fun run(name: String): String {
                    if (enabled()) return external.Reader.hardcoded()
                    return external.Reader.identity(name.trim())
                }
                fun dynamic(name: String) = external.Reader.dynamic(name)
                fun callback() = external.Reader.callback { java.util.ResourceBundle.getBundle("messages").getString("unused") }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("application"))
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-classpath", "$library:$dependency",
                "-jvm-target", "17", "-d", classes.toString(), source.toString())
        }
        assertEquals(ExitCode.OK, result, output.toString())
        return classes
    }
}
