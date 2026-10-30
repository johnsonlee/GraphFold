package io.johnsonlee.graphfold.engine.graphite

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BytecodeInventoryTest {
    @TempDir lateinit var directory: Path

    @Test fun `record annotations generic fields and dynamic access have explicit inventory evidence`() {
        val source = directory.resolve("Model.java")
        Files.writeString(source, """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE, ElementType.PARAMETER})
            @interface Marker { Class<?> type() default String.class; ElementType kind() default ElementType.TYPE_USE; }
            @Retention(RetentionPolicy.CLASS) @interface Invisible {}
            record Point(@Marker int x) {}
            class Model {
                java.util.List<@Marker String> field;
                @Invisible private void callback(@Marker Object arg) {}
                private void plain() {}
                int[][] arrays(Object value) { Object[] array = (Object[])value; return new int[array.length][2]; }
                void reflect(java.lang.reflect.Method method, ClassLoader loader) throws Exception {
                    method.invoke(this);
                    java.lang.invoke.MethodHandles.lookup();
                    loader.loadClass("x.Y");
                    Model.class.desiredAssertionStatus();
                }
            }
        """.trimIndent())
        assertTrue(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue(inventory.declarations.getValue("Point").contains("java/lang/Record"))
        assertTrue(inventory.declarations.getValue("Model").contains("type-annotation"))
        assertTrue(inventory.diagnostics.any { it.contains("callback") && it.contains("framework") })
        assertFalse(inventory.diagnostics.any { it.contains("plain") })
        assertTrue(inventory.diagnostics.any { it.contains("MethodHandles") })
        assertTrue(inventory.diagnostics.any { it.contains("ClassLoader") })
        assertTrue(inventory.diagnostics.any { it.contains("reflect/Method") })
        assertTrue(inventory.missingReferences(emptyList()).isEmpty(), inventory.references.toString())
    }

    @Test fun `unknown attributes and constant dynamic dependencies cannot disappear in inventory`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Generated", null, "java/lang/Object", null)
        writer.visitAttribute(opaqueAttribute())
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/Object;", null, null)
        method.visitAttribute(opaqueAttribute())
        method.visitCode()
        val bootstrap = Handle(
            Opcodes.H_INVOKESTATIC, "missing/Bootstrap", "produce",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false,
        )
        method.visitLdcInsn(ConstantDynamic("a", "Ljava/lang/Object;", bootstrap, "argument"))
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        writer.visitEnd()
        Files.write(directory.resolve("Generated.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue(inventory.diagnostics.any { it.contains("class attributes") })
        assertTrue(inventory.diagnostics.any { it.contains("method attributes") })
        assertTrue("missing.Bootstrap" in inventory.missingReferences(emptyList()))
    }

    @Test fun `serialization callbacks are distinguished from static methods with the same name`() {
        val inventory = compileInventory("""
            class Model implements java.io.Serializable {
                private void readObject(java.io.ObjectInputStream input) {}
                private void writeObject(java.io.ObjectOutputStream output) {}
                private void readObjectNoData() {}
                private Object readResolve() { return this; }
                private Object writeReplace() { return this; }
            }
            class Utility { private static Object readResolve() { return null; } }
        """.trimIndent())
        kotlin.test.assertEquals(5, inventory.diagnostics.count { it.contains("serialization callback") })
        assertFalse(inventory.diagnostics.any { it.contains("Utility") })
    }

    @Test fun `volatile inherited annotated and type annotated fields cannot be treated as private rename safe`() {
        val inventory = compileInventory("""
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD) @interface Discover {}
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface VisibleType {}
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE_USE) @interface HiddenType {}
            class Model {
                private int unused;
                private volatile int concurrent;
                @Discover private int discovered;
                private @VisibleType String visible;
                private @HiddenType String hidden;
                private int read;
                int get() { return read; }
            }
            class Child extends Model { private int inherited; }
            class Contract implements Runnable { private int value; public void run() {} }
        """.trimIndent())
        kotlin.test.assertEquals(setOf("Model.unused:I"), inventory.ignoredFields)
        assertTrue(inventory.fields.getValue("Model.read:I").renameSafe)
        assertFalse(inventory.fields.getValue("Child.inherited:I").renameSafe)
        assertFalse(inventory.fields.getValue("Contract.value:I").renameSafe)
    }

    @Test fun `field method handles count as reads and dependency directories may contain nested jars`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Handles", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd()
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "handle", "()Ljava/lang/Object;", null, null)
        method.visitCode()
        method.visitLdcInsn(Handle(Opcodes.H_GETFIELD, "Handles", "value", "Ljava/lang/String;", false))
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        writer.visitEnd()
        val application = Files.createDirectory(directory.resolve("application"))
        Files.write(application.resolve("Handles.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(application) }
        assertTrue(inventory.ignoredFields.isEmpty())
        val dependencies = Files.createDirectory(directory.resolve("dependencies"))
        java.util.jar.JarOutputStream(Files.newOutputStream(dependencies.resolve("support.jar"))).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("Handles.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
        }
        Files.writeString(dependencies.resolve("README.txt"), "Non-class resources are not dependencies")
        assertTrue(inventory.missingReferences(listOf(dependencies)).isEmpty())
    }

    @Test fun `application directory includes classes supplied as sibling jars`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Packaged", null, "java/lang/Object", null)
        writer.visitEnd()
        java.util.jar.JarOutputStream(Files.newOutputStream(directory.resolve("module.jar"))).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("Packaged.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
        }
        val inventory = BytecodeInventory().also { it.read(directory) }
        kotlin.test.assertEquals(setOf("Packaged"), inventory.classes)
        assertTrue("Packaged" in inventory.entryClasses)
        assertTrue(inventory.diagnostics.isEmpty())
    }

    private fun compileInventory(source: String): BytecodeInventory {
        val file = directory.resolve("Model.java")
        Files.writeString(file, source)
        kotlin.test.assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", directory.toString(), file.toString(),
        ))
        return BytecodeInventory().also { it.read(directory) }
    }

    private fun opaqueAttribute(): Attribute = object : Attribute("OpaqueSemanticData") {
        override fun write(writer: ClassWriter, code: ByteArray?, length: Int, stack: Int, locals: Int): ByteVector =
            ByteVector().putByte(1)
    }
}
