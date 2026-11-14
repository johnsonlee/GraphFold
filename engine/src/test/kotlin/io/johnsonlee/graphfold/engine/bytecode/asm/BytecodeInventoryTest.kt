package io.johnsonlee.graphfold.engine.bytecode.asm

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BytecodeInventoryTest {
    @TempDir lateinit var directory: Path

    @Test fun `inventory rejects an archive instead of adapting it as an application`() {
        val archive = directory.resolve("input.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(archive)).use { }
        val failure = assertFailsWith<IllegalArgumentException> { BytecodeInventory().read(archive) }
        assertTrue("prepared class directory" in failure.message.orEmpty())
    }

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
        assertTrue(inventory.methodDiagnostics.values.flatten().any { it.message.contains("MethodHandles") && it.externalObservationOnly })
        assertTrue(inventory.methodDiagnostics.values.flatten().any { it.message.contains("ClassLoader") })
        assertTrue(inventory.methodDiagnostics.values.flatten().any { it.message.contains("reflect/Method") })
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

    @Test fun `native library loading retains a global dynamic entry guard for every supported JDK entry point`() {
        val inventory = compileInventory("""
            class NativeLoads {
                void loadAll(Runtime runtime, String path) {
                    System.load(path);
                    System.loadLibrary(path);
                    runtime.load(path);
                    runtime.loadLibrary(path);
                }
                String property() { return System.getProperty("java.library.path"); }
                void ordinary() { load("plain"); }
                void load(String path) {}
            }
        """.trimIndent())
        val gaps = inventory.methodDiagnostics.getValue("NativeLoads.loadAll(Ljava/lang/Runtime;Ljava/lang/String;)V")
        kotlin.test.assertEquals(setOf(
            "java.lang.System.load(Ljava/lang/String;)V", "java.lang.System.loadLibrary(Ljava/lang/String;)V",
            "java.lang.Runtime.load(Ljava/lang/String;)V", "java.lang.Runtime.loadLibrary(Ljava/lang/String;)V",
        ), gaps.map { it.callee }.toSet())
        assertTrue(gaps.all { it.globalObservation && !it.dispatchOnly })
        assertFalse("NativeLoads.property()Ljava/lang/String;" in inventory.methodDiagnostics)
        assertFalse("NativeLoads.ordinary()V" in inventory.methodDiagnostics)
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

    @Test fun `embedded resource jars cannot introduce application declarations`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Packaged", null, "java/lang/Object", null)
        writer.visitEnd()
        java.util.jar.JarOutputStream(Files.newOutputStream(directory.resolve("module.jar"))).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("Packaged.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
        }
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue(inventory.classes.isEmpty())
        assertTrue(inventory.entryClasses.isEmpty())
        assertTrue(inventory.diagnostics.isEmpty())
    }

    @Test fun `class name value model does not erase normalized anonymous runtime identities`() {
        val ordinary = compileInventory("""
            class Model {
                String name() { return new Object() {}.getClass().getName(); }
                String type() { return new Object() {}.getClass().getTypeName(); }
            }
        """.trimIndent())
        assertTrue(ordinary.methodDiagnostics.isEmpty())
        val normalized = BytecodeInventory(normalizedRuntimeNames = true).also { it.read(directory) }
        kotlin.test.assertEquals(2, normalized.methodDiagnostics.values.flatten().size)
        assertTrue(normalized.methodDiagnostics.values.flatten().all { it.globalObservation && !it.externalObservationOnly })
    }

    @Test fun `module descriptors without a superclass remain inventory declarations`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null)
        writer.visitModule("sample.application", 0, null).visitEnd()
        writer.visitEnd()
        Files.write(directory.resolve("module-info.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(directory) }
        assertTrue("module-info" in inventory.declarations)
        assertTrue(inventory.references.isEmpty())
    }

    @Test fun `only the exact compiler bootstrap Lookup entry is ignored`() {
        val flags = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        fun shape(label: String, outer: String?, inner: String?, access: Int): String {
            val root = Files.createDirectory(directory.resolve(label))
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Sample", null, "java/lang/Object", null)
            writer.visitInnerClass("java/lang/invoke/MethodHandles\$Lookup", outer, inner, access)
            writer.visitEnd()
            Files.write(root.resolve("Sample.class"), writer.toByteArray())
            return BytecodeInventory().also { it.read(root) }.declarations.getValue("Sample")
        }
        val bootstrap = shape("bootstrap", "java/lang/invoke/MethodHandles", "Lookup", flags)
        assertFalse("java/lang/invoke/MethodHandles\$Lookup" in bootstrap)
        listOf(shape("owner", "sample/Owner", "Lookup", flags),
            shape("name", "java/lang/invoke/MethodHandles", "RenamedLookup", flags),
            shape("flags", "java/lang/invoke/MethodHandles", "Lookup", Opcodes.ACC_PUBLIC)).forEach { declaration ->
            assertTrue("java/lang/invoke/MethodHandles\$Lookup" in declaration)
            kotlin.test.assertNotEquals(bootstrap, declaration)
        }
    }

    @Test fun `an interface static property factory is not the trusted stdlib adapter`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "InterfaceFactory", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "token",
            "(Lkotlin/jvm/internal/PropertyReference1;)Lkotlin/reflect/KProperty1;", null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Reflection", "property1",
            "(Lkotlin/jvm/internal/PropertyReference1;)Lkotlin/reflect/KProperty1;", true)
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 1)
        method.visitEnd()
        writer.visitEnd()
        Files.write(directory.resolve("InterfaceFactory.class"), writer.toByteArray())
        val inventory = BytecodeInventory().also { it.read(directory) }
        val stdlib = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
        inventory.missingReferences(listOf(stdlib))
        val gap = inventory.methodDiagnostics.values.flatten().single()
        assertTrue(gap.globalObservation)
        assertTrue("Reflection.property1" in gap.message)
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
