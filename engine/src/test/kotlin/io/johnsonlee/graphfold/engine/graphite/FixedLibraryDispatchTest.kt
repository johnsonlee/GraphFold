package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.LeafApplicationDispatch

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedLibraryDispatchTest {
    @TempDir lateinit var directory: Path
    private val caller = MethodDescriptor(TypeDescriptor("Caller"), "run", emptyList(), TypeDescriptor("java.lang.String"))
    private val callee = MethodDescriptor(TypeDescriptor("App"), "value", emptyList(), TypeDescriptor("java.lang.String"))
    private val site = CallSiteNode(NodeId(1), caller, callee, 1, null, emptyList(), 0)
    private val methods get() = mapOf(caller.identity to MethodSnapshot(caller.identity, "body", setOf(callee.identity),
        setOf(MethodModifier.PUBLIC), emptySet()))

    @Test fun `final library owners fix dispatch without promoting them to application roots`() {
        write("App", type("App", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL))
        Files.writeString(directory.resolve("README.txt"), "ordinary resource")
        assertTrue(LeafApplicationDispatch(directory, setOf("Caller")).proves(site, methods))
    }

    @ParameterizedTest
    @ValueSource(ints = [Opcodes.ACC_FINAL, Opcodes.ACC_STATIC, Opcodes.ACC_PRIVATE])
    fun `nonoverridable library declarations fix dispatch even when the owner has subtypes`(access: Int) {
        write("App", type("App", methodAccess = access))
        write("Child", type("Child", parent = "App", method = false))
        assertTrue(LeafApplicationDispatch(directory, setOf("Caller")).proves(site, methods))
    }

    @Test fun `final JDK owner and final JDK method use runtime declaration evidence`() {
        val targets = listOf(
            MethodDescriptor(TypeDescriptor("java.net.URI"), "getHost", emptyList(), TypeDescriptor("java.lang.String")),
            MethodDescriptor(TypeDescriptor("java.net.HttpCookie"), "hasExpired", emptyList(), TypeDescriptor("boolean")),
            MethodDescriptor(TypeDescriptor("java.io.RandomAccessFile"), "getChannel", emptyList(),
                TypeDescriptor("java.nio.channels.FileChannel")),
        )
        for (target in targets) {
            val call = site.copy(callee = target)
            val snapshot = methods.mapValues { (_, method) -> method.copy(calls = setOf(target.identity)) }
            assertTrue(proof().proves(call, snapshot), target.identity)
        }
        val overridable = MethodDescriptor(TypeDescriptor("java.io.RandomAccessFile"), "length", emptyList(), TypeDescriptor("long"))
        assertFalse(proof().proves(site.copy(callee = overridable),
            methods.mapValues { (_, method) -> method.copy(calls = setOf(overridable.identity)) }))
    }

    @Test fun `fixed dispatch still requires complete inputs and a surviving direct call`() {
        write("App", type("App", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL))
        val library = LeafApplicationDispatch(directory, emptySet())
        assertFalse(library.proves(site.copy(ordinal = -1), methods))
        assertFalse(library.proves(site, emptyMap()))
        write("App", type("App", parent = "Missing", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL))
        assertFalse(LeafApplicationDispatch(directory, emptySet()).proves(site, methods))
    }

    @Test fun `optional unrelated dependency hierarchy does not invalidate a final JDK call`() {
        val application = Files.createDirectory(directory.resolve("application"))
        val dependency = Files.createDirectory(directory.resolve("dependency"))
        val source = directory.resolve("Caller.java")
        Files.writeString(source, """
            public class Caller {
                public static java.nio.channels.FileChannel channel(java.io.RandomAccessFile file) {
                    return file.getChannel();
                }
                public static long length(java.io.RandomAccessFile file) throws java.io.IOException {
                    return file.length();
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", application.toString(), source.toString()))
        Files.write(dependency.resolve("Optional.class"), type("Optional", parent = "Unavailable", method = false))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency))
        val gaps = snapshot.methodDiagnostics.values.flatten().filter { it.dispatchOnly }
        assertFalse(gaps.any { it.callee == "java.io.RandomAccessFile.getChannel()Ljava/nio/channels/FileChannel;" },
            gaps.toString())
        assertTrue(gaps.any { it.callee == "java.io.RandomAccessFile.length()J" }, gaps.toString())
    }

    @Test fun `an unrelated missing hierarchy still prevents application leaf dispatch proof`() {
        write("App", type("App"))
        write("Broken", type("Broken", parent = "Missing", method = false))
        assertFalse(proof().proves(site, methods))
        write("App", type("App", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL))
        assertTrue(proof().proves(site, methods))
    }

    @Test fun `runtime subclass scopes retain only JVM nonoverridable dispatch evidence`() {
        write("App", type("App"))
        assertFalse(LeafApplicationDispatch(directory, setOf("App"), allowApplicationLeaf = false).proves(site, methods))
        assertTrue(LeafApplicationDispatch(directory, setOf("App")).proves(site, methods))
        for (access in listOf(Opcodes.ACC_FINAL, Opcodes.ACC_STATIC, Opcodes.ACC_PRIVATE)) {
            write("App", type("App", methodAccess = access))
            assertTrue(LeafApplicationDispatch(directory, setOf("App"), allowApplicationLeaf = false).proves(site, methods))
        }
        write("App", type("App", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL))
        assertTrue(LeafApplicationDispatch(directory, setOf("App"), allowApplicationLeaf = false).proves(site, methods))
    }

    private fun proof() = LeafApplicationDispatch(directory, setOf("App"))
    private fun write(name: String, bytes: ByteArray) { Files.write(directory.resolve("$name.class"), bytes) }
    private fun type(
        name: String, parent: String = "java/lang/Object", method: Boolean = true,
        access: Int = Opcodes.ACC_PUBLIC, methodAccess: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, access, name, null, parent, null)
        if (method) visitMethod(methodAccess, "value", "()Ljava/lang/String;", null, null).visitEnd()
        visitEnd()
    }.toByteArray()
}
