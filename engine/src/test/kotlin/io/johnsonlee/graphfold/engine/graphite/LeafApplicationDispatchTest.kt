package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.LeafApplicationDispatch

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import sootup.core.model.MethodModifier
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeafApplicationDispatchTest {
    @TempDir lateinit var directory: Path
    private val caller = MethodDescriptor(TypeDescriptor("Caller"), "run", emptyList(), TypeDescriptor("java.lang.String"))
    private val callee = MethodDescriptor(TypeDescriptor("App"), "value", emptyList(), TypeDescriptor("java.lang.String"))
    private val site = CallSiteNode(NodeId(1), caller, callee, 1, null, emptyList(), 0)
    private val methods get() = mapOf(caller.identity to MethodSnapshot(caller.identity, "body", setOf(callee.identity),
        setOf(MethodModifier.PUBLIC), emptySet()))

    @Test fun `parentless classes and supplied platform shadows cannot establish the runtime hierarchy`() {
        write("App", type("App", parent = null))
        assertFalse(proof().proves(site, methods))
        write("App", type("App"))
        write("Platform", type("java/lang/String"))
        assertFalse(proof().proves(site, methods))
        write("Platform", type("java/lang/Object", parent = null))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `duplicate archive entries invalidate inventory even when class entries are unique`() {
        val jar = archive("duplicate-entries.jar", mapOf("App.class" to type("App"),
            "copy-a.txt" to byteArrayOf(1), "copy-b.txt" to byteArrayOf(2)))
        val bytes = Files.readAllBytes(jar)
        val before = "copy-b.txt".toByteArray()
        val after = "copy-a.txt".toByteArray()
        for (offset in 0..bytes.size - before.size) {
            if (before.indices.all { bytes[offset + it] == before[it] }) after.copyInto(bytes, offset)
        }
        Files.write(jar, bytes)
        assertFalse(LeafApplicationDispatch(jar, setOf("App")).proves(site, methods))
    }

    @Test fun `versioned resources without versioned executable classes preserve a single hierarchy`() {
        val jar = archive("resources.jar", mapOf("App.class" to type("App"),
            "META-INF/versions/9/messages.txt" to byteArrayOf(1)))
        assertTrue(LeafApplicationDispatch(jar, setOf("App")).proves(site, methods))
    }

    @Test fun `nonfinal application leaf with a declared method and direct folded call has exact dispatch`() {
        write("App", type("App"))
        write("Caller", type("Caller"))
        assertTrue(proof().proves(site, methods))
    }

    @Test fun `a library leaf is not promoted to an application dispatch proof`() {
        write("App", type("App"))
        assertFalse(LeafApplicationDispatch(directory, setOf("Caller")).proves(site, methods))
    }

    @Test fun `missing owner missing declaration and abstract declaration cannot prove a target`() {
        assertFalse(proof().proves(site, methods))
        write("App", type("App", method = false))
        assertFalse(proof().proves(site, methods))
        write("App", type("App", abstract = true))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `derived unknown and removed calls cannot borrow a leaf declaration`() {
        write("App", type("App"))
        val proof = proof()
        assertFalse(proof.proves(site.copy(origin = site.id), methods))
        assertFalse(proof.proves(site.copy(ordinal = -1), methods))
        assertFalse(proof.proves(site.copy(ordinal = null), methods))
        assertFalse(proof.proves(site, emptyMap()))
        assertFalse(proof.proves(site, methods.mapValues { (_, method) -> method.copy(calls = emptySet()) }))
    }

    @Test fun `abstract and interface owners need a concrete runtime receiver beyond a leaf header`() {
        for (access in listOf(Opcodes.ACC_ABSTRACT, Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)) {
            write("App", type("App", access = Opcodes.ACC_PUBLIC or access))
            assertFalse(proof().proves(site, methods))
        }
    }

    @Test fun `any subtype prevents the proof even without an override`() {
        write("App", type("App"))
        write("Child", type("Child", parent = "App", method = false))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `a dependency subclass prevents treating an application class as a leaf`() {
        write("App", type("App"))
        archive("dependency.jar", mapOf("Child.class" to type("Child", parent = "App")))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `interface implementations and missing or cyclic hierarchy retain uncertainty`() {
        write("App", type("App"))
        write("Child", type("Child", interfaces = arrayOf("App")))
        assertFalse(proof().proves(site, methods))
        write("Child", type("Child", parent = "Missing"))
        assertFalse(proof().proves(site, methods))
        write("Child", type("Child", parent = "Other"))
        write("Other", type("Other", parent = "Child"))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `duplicate class definitions cannot establish a leaf inventory`() {
        write("App", type("App"))
        archive("duplicate.jar", mapOf("App.class" to type("App")))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `a jar inventory reads class declarations and ignores ordinary resources and directories`() {
        val jar = archive("application.jar", mapOf("App.class" to type("App"), "messages.txt" to byteArrayOf(1), "empty/" to byteArrayOf()))
        assertTrue(LeafApplicationDispatch(jar, setOf("App")).proves(site, methods))
    }

    @Test fun `raw versioned module metadata and class variants require adapter normalization`() {
        val module = ClassWriter(0).apply {
            visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
            visitModule("fixture.module", 0, null).visitEnd()
            visitEnd()
        }.toByteArray()
        val jar = archive("application.jar", mapOf("App.class" to type("App"),
            "module-info.class" to module, "META-INF/versions/9/module-info.class" to module))
        assertFalse(LeafApplicationDispatch(jar, setOf("App")).proves(site, methods))
        archive("application.jar", mapOf("App.class" to type("App"), "META-INF/versions/9/App.class" to type("App")))
        assertFalse(LeafApplicationDispatch(jar, setOf("App")).proves(site, methods))
    }

    @ParameterizedTest
    @ValueSource(strings = ["nested.jar", "nested.zip", "nested.war", "nested.ear"])
    fun `nested archives and unpacked unsupported archives cannot prove a closed hierarchy`(name: String) {
        write("App", type("App"))
        val jar = archive("dependency.jar", mapOf(name to byteArrayOf(1)))
        assertFalse(proof().proves(site, methods))
        Files.delete(jar)
        Files.write(directory.resolve(name), byteArrayOf(1))
        assertFalse(proof().proves(site, methods))
    }

    @Test fun `unreadable archives malformed classes and unsupported class versions fail closed`() {
        write("App", type("App"))
        val jar = directory.resolve("bad.jar")
        Files.write(jar, byteArrayOf(1))
        assertFalse(proof().proves(site, methods))
        Files.delete(jar)
        write("Broken", byteArrayOf(1))
        assertFalse(proof().proves(site, methods))
        write("Broken", type("Broken").apply { this[6] = 127; this[7] = 127 })
        assertFalse(proof().proves(site, methods))
    }

    private fun proof() = LeafApplicationDispatch(directory, setOf("App"))
    private fun write(name: String, bytes: ByteArray) { Files.write(directory.resolve("$name.class"), bytes) }
    private fun archive(name: String, entries: Map<String, ByteArray>): Path = directory.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (entry, bytes) ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        } }
    }
    private fun type(
        name: String, parent: String? = "java/lang/Object", interfaces: Array<String> = emptyArray(),
        method: Boolean = true, abstract: Boolean = false, access: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, access, name, null, parent, interfaces)
        if (method) visitMethod(Opcodes.ACC_PUBLIC or if (abstract) Opcodes.ACC_ABSTRACT else 0,
            "value", "()Ljava/lang/String;", null, null).visitEnd()
        visitEnd()
    }.toByteArray()
}
