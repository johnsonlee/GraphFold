package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegateMetadataInventoryTest {
    @TempDir lateinit var directory: Path
    private val stdlib: Path get() = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
    private val adapter = "(Lkotlin/jvm/internal/PropertyReference1;)Lkotlin/reflect/KProperty1;"

    @Test fun `stdlib metadata adapter is accepted but actual property inspection remains blocked`() {
        val inventory = calls(listOf(
            Call("kotlin/jvm/internal/Reflection", "property1", adapter),
            Call("kotlin/reflect/KProperty", "getName", "()Ljava/lang/String;", Opcodes.INVOKEINTERFACE),
        ))
        inventory.missingReferences(listOf(stdlib))
        assertEquals(1, inventory.diagnostics.size)
        assertTrue(inventory.diagnostics.single().contains("KProperty.getName"))
        assertTrue("kotlin.jvm.internal.PropertyReference1" in inventory.references)
    }

    @Test fun `near match signatures owners and invocation kinds do not bypass reflection checks`() {
        val inventory = calls(listOf(
            Call("kotlin/jvm/internal/Reflection", "property1", adapter),
            Call("kotlin/jvm/internal/Reflection", "property1", "(Ljava/lang/Object;)Lkotlin/reflect/KProperty1;"),
            Call("kotlin/jvm/internal/Reflection", "property1", adapter, Opcodes.INVOKEVIRTUAL),
            Call("kotlin/jvm/internal/ReflectionFactory", "property1", adapter, Opcodes.INVOKEVIRTUAL),
            Call("kotlin/jvm/internal/Reflection", "property0", adapter),
        ))
        inventory.missingReferences(listOf(stdlib))
        assertEquals(4, inventory.diagnostics.size)
    }

    @Test fun `full reflection factory keeps the adapter explicitly unsupported`() {
        val inventory = calls(listOf(Call("kotlin/jvm/internal/Reflection", "property1", adapter)))
        val dependency = directory.resolve("dependency")
        val name = "kotlin/reflect/jvm/internal/ReflectionFactoryImpl"
        val file = dependency.resolve("$name.class")
        Files.createDirectories(file.parent)
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        Files.write(file, writer.toByteArray())
        inventory.missingReferences(listOf(stdlib, dependency))
        assertEquals(1, inventory.diagnostics.size)
    }

    @Test fun `missing runtime cannot authorize the adapter`() {
        val inventory = calls(listOf(Call("kotlin/jvm/internal/Reflection", "property1", adapter)))
        inventory.missingReferences(emptyList())
        assertEquals(1, inventory.diagnostics.size)
    }

    @Test fun `replacement factory in dependency directory or jar cannot authorize metadata adapter`() {
        val dependency = directory.resolve("replacement")
        val name = "kotlin/jvm/internal/ReflectionFactory"
        val file = dependency.resolve("$name.class")
        Files.createDirectories(file.parent)
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        Files.write(file, writer.toByteArray())
        val reflectionName = "kotlin/jvm/internal/Reflection.class"
        val reflection = requireNotNull(Unit::class.java.getResourceAsStream("/$reflectionName")).use { it.readBytes() }
        Files.write(dependency.resolve(reflectionName), reflection)
        val archive = directory.resolve("replacement.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("$name.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
            jar.putNextEntry(java.util.jar.JarEntry(reflectionName))
            jar.write(reflection)
            jar.closeEntry()
        }
        for (replacement in listOf(dependency, archive)) {
            val inventory = calls(listOf(Call("kotlin/jvm/internal/Reflection", "property1", adapter)))
            inventory.missingReferences(listOf(replacement))
            assertEquals(1, inventory.diagnostics.size)
            inventory.missingReferences(listOf(stdlib, replacement))
            assertEquals(1, inventory.diagnostics.size)
        }
    }

    @Test fun `dependency recheck restores diagnostics and rejects duplicate trusted runtimes`() {
        val inventory = calls(listOf(Call("kotlin/jvm/internal/Reflection", "property1", adapter)))
        inventory.missingReferences(listOf(stdlib))
        assertTrue(inventory.diagnostics.isEmpty())
        inventory.missingReferences(listOf(stdlib, stdlib))
        assertEquals(1, inventory.diagnostics.size)
        inventory.missingReferences(listOf(stdlib))
        assertTrue(inventory.diagnostics.isEmpty())
        inventory.missingReferences(emptyList())
        assertEquals(1, inventory.diagnostics.size)
    }

    @Test fun `application shadowing of reflection factory prevents trusted stdlib exemption`() {
        val inventory = calls(listOf(Call("kotlin/jvm/internal/Reflection", "property1", adapter)))
        val shadow = directory.resolve("shadow")
        val name = "kotlin/jvm/internal/ReflectionFactory"
        val path = shadow.resolve("$name.class")
        Files.createDirectories(path.parent)
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        Files.write(path, writer.toByteArray())
        inventory.read(shadow)
        inventory.missingReferences(listOf(stdlib))
        assertEquals(1, inventory.diagnostics.size)
        assertTrue(inventory.diagnostics.single().contains("Reflection.property1"))
    }

    private data class Call(
        val owner: String,
        val name: String,
        val descriptor: String,
        val opcode: Int = Opcodes.INVOKESTATIC,
    )

    private fun calls(calls: List<Call>): BytecodeInventory {
        val output = directory.resolve("application")
        Files.createDirectories(output)
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Tokens", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "inspect", "()V", null, null)
        method.visitCode()
        calls.forEach { call ->
            if (call.opcode != Opcodes.INVOKESTATIC) method.visitInsn(Opcodes.ACONST_NULL)
            if (call.descriptor.startsWith("(L")) method.visitInsn(Opcodes.ACONST_NULL)
            method.visitMethodInsn(call.opcode, call.owner, call.name, call.descriptor, call.opcode == Opcodes.INVOKEINTERFACE)
            method.visitInsn(Opcodes.POP)
        }
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 0)
        method.visitEnd()
        writer.visitEnd()
        Files.write(output.resolve("Tokens.class"), writer.toByteArray())
        return BytecodeInventory().also { it.read(output) }
    }
}
