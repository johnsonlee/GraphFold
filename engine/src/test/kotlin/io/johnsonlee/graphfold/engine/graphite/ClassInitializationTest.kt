package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassInitializationTest {
    @Test fun `class initialization follows superclass and default interfaces but not static-only interfaces`() {
        val inventory = ClassInitialization()
        inventory.add(type("Root", interfaces = listOf("Default", "Constants")))
        inventory.add(type("Child", parent = "Root"))
        inventory.add(type("Default", isInterface = true, defaultMethod = true))
        inventory.add(type("Constants", isInterface = true))
        assertEquals(setOf("Root", "java.lang.Object", "Default"), inventory.dependencies().getValue("Child"))
        assertEquals(setOf("java.lang.Object", "Default"), inventory.dependencies().getValue("Root"))
    }

    @Test fun `initializing an interface alone does not initialize its superinterfaces`() {
        val inventory = ClassInitialization()
        inventory.add(type("Parent", isInterface = true, defaultMethod = true))
        inventory.add(type("Child", interfaces = listOf("Parent"), isInterface = true, defaultMethod = true))
        assertTrue(inventory.dependencies().getValue("Child").isEmpty())
    }

    @Test fun `class initializes recursive default interfaces even through nondefault interface`() {
        val inventory = ClassInitialization()
        inventory.add(type("Parent", isInterface = true, defaultMethod = true))
        inventory.add(type("Bridge", interfaces = listOf("Parent"), isInterface = true))
        inventory.add(type("Root", interfaces = listOf("Bridge", "Parent", "Missing")))
        assertEquals(setOf("java.lang.Object", "Parent"), inventory.dependencies().getValue("Root"))
    }

    @Test fun `malformed cycles terminate and root without superclass is supported`() {
        val inventory = ClassInitialization()
        inventory.add(type("First", parent = "Second", interfaces = listOf("Cycle")))
        inventory.add(type("Second", parent = "First"))
        inventory.add(type("Cycle", interfaces = listOf("Cycle"), isInterface = true, defaultMethod = true))
        inventory.add(type("java/lang/Object", parent = null))
        assertEquals(setOf("First", "Second", "Cycle"), inventory.dependencies().getValue("First"))
        assertTrue(inventory.dependencies().getValue("java.lang.Object").isEmpty())
    }

    private fun type(
        name: String, parent: String? = "java/lang/Object", interfaces: List<String> = emptyList(),
        isInterface: Boolean = false, defaultMethod: Boolean = false,
    ): ClassNode = ClassNode().apply {
        this.name = name
        superName = parent
        this.interfaces.addAll(interfaces)
        access = if (isInterface) Opcodes.ACC_INTERFACE else Opcodes.ACC_PUBLIC
        methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null))
        methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "abstractMethod", "()V", null, null))
        methods.add(MethodNode(Opcodes.ACC_PRIVATE, "privateMethod", "()V", null, null))
        if (defaultMethod) methods.add(MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null))
    }
}
