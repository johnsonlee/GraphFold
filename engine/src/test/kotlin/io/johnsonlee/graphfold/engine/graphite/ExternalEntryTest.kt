package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalEntryTest {
    @TempDir lateinit var directory: Path

    @Test fun `private Java serialization callbacks cannot be mistaken for dead helpers`() {
        val source = directory.resolve("Sample.java")
        Files.writeString(source, """
            public class Sample {
                static void work() {}
                static class Form implements java.io.Serializable {
                    private void readObject(java.io.ObjectInputStream in) { work(); }
                    private void writeObject(java.io.ObjectOutputStream out) { work(); }
                    private void readObjectNoData() { work(); }
                    private Object readResolve() { work(); return this; }
                    private Object writeReplace() { work(); return this; }
                    private void readObject(int unrelatedOverload) {}
                }
                static class NotCallback {
                    private static Object readResolve() { return null; }
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val result = GraphiteAnalyzer().analyze(directory, emptyList())
        val callbacks = result.diagnostics.filter { it.contains("serialization callback") }
        assertEquals(5, callbacks.size, callbacks.toString())
        assertTrue(callbacks.all { it.contains("Sample\$Form.") })
        assertFalse(callbacks.any { it.contains("readObject(I)") || it.contains("NotCallback") })
    }
    @Test fun `runtime discovered classes and methods remain external entries without ordinary callers`() {
        val source = directory.resolve("Sample.java")
        Files.writeString(source, """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Entry {}
            @Entry class Discovered {
                private Discovered() {}
                public void handle() {}
                private void helper() {}
            }
            public class Sample {
                @Entry public void handle() {}
                @Entry private void callback() {}
                public void ordinary() {}
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue("Discovered.<init>()V" in inventory.externalEntries)
        assertTrue("Discovered.handle()V" in inventory.externalEntries)
        assertTrue("Sample.handle()V" in inventory.externalEntries)
        assertTrue("Sample.callback()V" in inventory.externalEntries)
        assertFalse("Discovered.helper()V" in inventory.externalEntries)
        assertFalse("Sample.ordinary()V" in inventory.externalEntries)
        assertTrue("Discovered" !in inventory.entryClasses)
        assertTrue("Sample" in inventory.entryClasses)
        assertTrue(inventory.diagnostics.any { "callback" in it && "framework" in it })
    }

    @Test fun `compiler nullability annotations are not framework callbacks`() {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Sample", null, "java/lang/Object", null)
        val method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "value", "()Ljava/lang/String;", null, null)
        method.visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd()
        method.visitCode()
        method.visitLdcInsn("value")
        method.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
        method.visitMaxs(1, 1)
        method.visitEnd()
        writer.visitEnd()
        Files.write(directory.resolve("Sample.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue(inventory.externalEntries.isEmpty())
        assertTrue(inventory.diagnostics.isEmpty())
    }

    @Test fun `direct Kotlin reflection and unreadable metadata produce blocking graph diagnostics`() {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Sample", null, "java/lang/Object", null)
        writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        val method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "inspect", "()V", null, null)
        method.visitCode()
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "kotlin/reflect/full/KClasses", "test", "()V", false)
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Reflection", "test", "()V", false)
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        method.visitMaxs(0, 1)
        method.visitEnd()
        writer.visitEnd()
        Files.write(directory.resolve("Sample.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue(inventory.diagnostics.any { "Unsupported Kotlin metadata" in it })
        assertEquals(2, inventory.diagnostics.count { "reflective or dynamic" in it })
    }

}
