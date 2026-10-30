package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InheritedEntriesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `new override of platform inherited API is recognized without an application caller`() {
        val child = type("Sample", "java/util/ArrayList")
        child.methods.add(method("isEmpty", "()Z"))
        child.methods.add(method("unused", "()I"))
        val inventory = InheritedEntries().also { it.add(child) }
        val diagnostics = mutableListOf<String>()
        assertEquals(setOf("Sample.isEmpty()Z"), inventory.resolve(emptyList(), diagnostics))
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `private and static members do not override inherited signatures`() {
        val parent = type("sample/Parent")
        parent.methods.add(method("hidden", "()V", Opcodes.ACC_PRIVATE))
        parent.methods.add(method("staticCall", "()V", Opcodes.ACC_STATIC))
        parent.methods.add(method("packageCall", "()V", 0))
        val child = type("other/Child", parent.name)
        child.methods.addAll(parent.methods.map { method(it.name, it.desc) })
        val inventory = InheritedEntries().also { it.add(parent); it.add(child) }
        assertTrue(inventory.resolve(emptyList(), mutableListOf()).isEmpty())
        val sibling = type("sample/Child", parent.name)
        sibling.methods.add(method("packageCall", "()V"))
        inventory.add(sibling)
        assertEquals(setOf("sample.Child.packageCall()V"), inventory.resolve(emptyList(), mutableListOf()))
    }

    @Test
    fun `dependency inherited interfaces resolve from directories and jars`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "dependency/Contract", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "invoke", "()I", null, null).visitEnd()
        writer.visitEnd()
        val classes = Files.createDirectories(directory.resolve("classes/dependency"))
        Files.write(classes.resolve("Contract.class"), writer.toByteArray())
        val jar = directory.resolve("contract.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(ZipEntry("dependency/Contract.class")); it.write(writer.toByteArray()); it.closeEntry()
        }
        val child = type("Sample").also { it.interfaces.add("dependency/Contract"); it.methods.add(method("invoke", "()I")) }
        val inventory = InheritedEntries().also { it.add(child) }
        val emptyDirectory = Files.createDirectory(directory.resolve("empty"))
        val emptyJar = directory.resolve("empty.jar")
        ZipOutputStream(Files.newOutputStream(emptyJar)).close()
        listOf(directory.resolve("classes"), jar).forEach { dependency ->
            val diagnostics = mutableListOf<String>()
            assertEquals(setOf("Sample.invoke()I"), inventory.resolve(listOf(emptyDirectory, emptyJar, dependency), diagnostics))
            assertTrue(diagnostics.isEmpty())
        }
    }

    @Test
    fun `missing transitive inherited APIs are explicit gaps`() {
        val inventory = InheritedEntries().also { it.add(type("Sample", "missing/Parent")) }
        val diagnostics = mutableListOf<String>()
        assertTrue(inventory.resolve(emptyList(), diagnostics).isEmpty())
        assertTrue(diagnostics.single().contains("Unresolved inherited API"))
    }

    @Test
    fun `diamond inherited interface has one callable override and keeps overloads separate`() {
        val contract = type("api/Contract").also {
            it.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            it.methods.add(method("accept", "(Ljava/lang/String;)V", Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT))
        }
        val left = type("api/Left").also { it.access = contract.access; it.interfaces.add(contract.name) }
        val right = type("api/Right").also { it.access = contract.access; it.interfaces.add(contract.name) }
        val child = type("app/Handler").also {
            it.interfaces.addAll(listOf(left.name, right.name))
            it.methods.add(method("accept", "(Ljava/lang/String;)V"))
            it.methods.add(method("accept", "(I)V"))
            it.methods.add(method("helper", "()V", Opcodes.ACC_PRIVATE))
            it.methods.add(method("utility", "()V", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC))
        }
        val inventory = InheritedEntries().also { entries -> listOf(contract, left, right, child).forEach(entries::add) }
        val diagnostics = mutableListOf<String>()
        assertEquals(setOf("app.Handler.accept(Ljava/lang/String;)V"), inventory.resolve(emptyList(), diagnostics))
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    private fun type(name: String, parent: String = "java/lang/Object") = ClassNode().also {
        it.name = name; it.superName = parent; it.access = Opcodes.ACC_PUBLIC
    }

    private fun method(name: String, descriptor: String, access: Int = Opcodes.ACC_PUBLIC) =
        MethodNode(access, name, descriptor, null, null)
}
