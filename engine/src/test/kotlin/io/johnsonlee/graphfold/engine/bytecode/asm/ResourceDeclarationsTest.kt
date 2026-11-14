package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ResourceDeclarationsTest {
    @TempDir lateinit var directory: Path

    @Test fun `directory and archive preserve declared methods and initializer hierarchy`() {
        val classes = Files.createDirectories(directory.resolve("classes"))
        val child = classes.resolve("example/Child.class")
        Files.createDirectories(child.parent)
        Files.write(child, type("example/Child", parent = "example/Parent", interfaces = arrayOf("example/Default")))
        Files.write(classes.resolve("README.txt"), byteArrayOf(1, 2, 3))
        val dependency = archive("dependency.jar", mapOf(
            "example/Parent.class" to type("example/Parent"),
            "example/Default.class" to type("example/Default", isInterface = true),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
        ))
        val declarations = ResourceDeclarations(listOf(classes, dependency, classes))
        assertEquals(setOf("example.Child", "example.Parent", "example.Default"), declarations.classes)
        assertTrue(declarations.methods.containsAll(setOf("example.Child.value()I", "example.Parent.value()I")))
        assertEquals(setOf("example.Parent", "java.lang.Object", "example.Default"), declarations.initialization()["example.Child"])
        assertTrue(declarations.diagnostics.isEmpty(), declarations.diagnostics.toString())
    }

    @Test fun `module descriptors archive directories and unrelated resources are ignored`() {
        val dependency = archive("module.jar", mapOf(
            "module-info.class" to byteArrayOf(0),
            "META-INF/versions/17/module-info.class" to byteArrayOf(0),
            "messages.properties" to "key=value".toByteArray(),
        ))
        val declarations = ResourceDeclarations(listOf(dependency))
        assertTrue(declarations.classes.isEmpty())
        assertTrue(declarations.methods.isEmpty())
        assertTrue(declarations.initialization().isEmpty())
        assertTrue(declarations.diagnostics.isEmpty())
    }

    @Test fun `duplicate binary owners across artifacts prevent claiming a complete inventory`() {
        val dependency = archive("first.jar", mapOf("example/Shared.class" to type("example/Shared")))
        val duplicate = archive("second.jar", mapOf("example/Shared.class" to type("example/Shared")))
        val declarations = ResourceDeclarations(listOf(dependency, duplicate))
        assertEquals(setOf("example.Shared"), declarations.classes)
        assertEquals(listOf("Duplicate resource dependency class: example.Shared"), declarations.diagnostics)
    }

    @Test fun `relocated classes and nested archives produce explicit omissions`() {
        val dependency = archive("packaged.jar", mapOf(
            "BOOT-INF/classes/example/Library.class" to type("example/Library"),
            "BOOT-INF/lib/hidden.jar" to byteArrayOf(0),
        ))
        val declarations = ResourceDeclarations(listOf(dependency))
        assertEquals(setOf("example.Library"), declarations.classes)
        val location = "Unsupported resource dependency class location: BOOT-INF/classes/example/Library.class"
        assertTrue(location in declarations.diagnostics)
        assertTrue(declarations.diagnostics.contains("Nested resource dependency archive: BOOT-INF/lib/hidden.jar"))
    }

    @Test fun `malformed class and invalid archive cannot silently become an empty successful inventory`() {
        val dependency = archive("broken.jar", mapOf("Broken.class" to byteArrayOf(0)))
        assertFailsWith<IndexOutOfBoundsException> { ResourceDeclarations(listOf(dependency)) }
        val invalid = directory.resolve("invalid.jar")
        Files.writeString(invalid, "not a zip archive")
        assertFailsWith<java.util.zip.ZipException> { ResourceDeclarations(listOf(invalid)) }
    }

    @Test fun `field lookup uses declaration then interfaces then superclass`() {
        val dependency = archive("fields.jar", mapOf(
            "Parent.class" to type("Parent", fields = listOf("PARENT")),
            "Values.class" to type("Values", isInterface = true, fields = listOf("VALUE"), hasDefault = false),
            "Child.class" to type("Child", parent = "Parent", interfaces = arrayOf("Values")),
            "Own.class" to type("Own", interfaces = arrayOf("Missing"), fields = listOf("VALUE")),
        ))
        val declarations = ResourceDeclarations(listOf(dependency))
        assertEquals("Values", declarations.fieldOwner("Child.VALUE:I"))
        assertEquals("Parent", declarations.fieldOwner("Child.PARENT:I"))
        assertEquals("Own", declarations.fieldOwner("Own.VALUE:I"))
        assertEquals(setOf("Values", "Parent"), declarations.entryInitializers("Child"))
        assertEquals(null, declarations.fieldOwner("Child.ABSENT:I"))
        assertEquals(null, declarations.fieldOwner("Unknown.VALUE:I"))
        assertEquals(setOf("Parent", "java.lang.Object"), declarations.initialization()["Child"])
    }

    @Test fun `ambiguous incomplete and cyclic field hierarchies are unknown`() {
        val dependency = archive("unknown-fields.jar", mapOf(
            "One.class" to type("One", isInterface = true, fields = listOf("VALUE")),
            "Two.class" to type("Two", isInterface = true, fields = listOf("VALUE")),
            "Ambiguous.class" to type("Ambiguous", interfaces = arrayOf("One", "Two")),
            "Missing.class" to type("Missing", interfaces = arrayOf("Absent", "One")),
            "Cycle.class" to type("Cycle", interfaces = arrayOf("Cycle")),
            "Root.class" to type("Root", parent = null),
        ))
        val declarations = ResourceDeclarations(listOf(dependency))
        listOf("Ambiguous", "Missing", "Cycle", "Root").forEach {
            assertEquals(null, declarations.fieldOwner("$it.VALUE:I"), it)
        }
        assertTrue("Absent" in declarations.initialization().getValue("Missing"))
        assertEquals(null, declarations.entryInitializers("Missing"))
        assertEquals(null, declarations.entryInitializers("Cycle"))
        assertEquals(null, declarations.entryInitializers("Unavailable"))
        assertEquals(emptySet(), declarations.entryInitializers("Root"))
        assertEquals(null, declarations.inheritedMethods("Missing"))
        assertEquals(null, declarations.inheritedMethods("Cycle"))
        assertEquals(null, declarations.inheritedMethods("Unavailable"))
        assertEquals(emptySet(), declarations.inheritedMethods("Root"))
    }

    @Test fun `unknown default interface initialization is retained only on affected classes`() {
        val dependency = archive("interfaces.jar", mapOf(
            "Affected.class" to type("Affected", interfaces = arrayOf("Unavailable")),
            "Ordinary.class" to type("Ordinary"),
            "Known.class" to type("Known", isInterface = true, interfaces = arrayOf("Unavailable")),
        ))
        val declarations = ResourceDeclarations(listOf(dependency))
        assertTrue("Unavailable" in declarations.initialization().getValue("Affected"))
        assertEquals(setOf("java.lang.Object"), declarations.initialization()["Ordinary"])
        assertTrue(declarations.initialization().getValue("Known").isEmpty())
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `private interface instance methods activate initialization without becoming inherited APIs`(static: Boolean) {
        val api = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "Api", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PRIVATE or if (static) Opcodes.ACC_STATIC else 0, "helper", "()V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
            visitEnd()
        }
        val objectClass = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "java/lang/Object", null, null, null)
            visitEnd()
        }
        val jar = archive("private-interface.jar", mapOf(
            "Api.class" to api.toByteArray(),
            "App.class" to type("App", interfaces = arrayOf("Api")),
            "java/lang/Object.class" to objectClass.toByteArray(),
        ))
        val inventory = ResourceDeclarations(listOf(jar))
        val expected = if (static) setOf("java.lang.Object") else setOf("java.lang.Object", "Api")
        assertEquals(expected, inventory.initialization().getValue("App"))
        assertEquals(emptySet(), inventory.inheritedMethods("App"))
        assertTrue(inventory.diagnostics.isEmpty())
    }

    @Test fun `Intrinsics contracts require exact runtime bytes and a unique supplied class`() {
        val library = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(ResourceDeclarations(listOf(library)).trustedIntrinsics)
        val fake = archive("fake-kotlin.jar", mapOf(
            "kotlin/jvm/internal/Intrinsics.class" to type("kotlin/jvm/internal/Intrinsics"),
        ))
        assertTrue(!ResourceDeclarations(listOf(fake)).trustedIntrinsics)
        val duplicate = ResourceDeclarations(listOf(library, fake))
        assertTrue(!duplicate.trustedIntrinsics)
        assertTrue(duplicate.diagnostics.any { "Duplicate resource dependency class" in it })
        assertTrue(!ResourceDeclarations(listOf(fake, library)).trustedIntrinsics)
    }

    @Test fun `ambiguous inherited default method targets are not treated as an exhaustive dispatch proof`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "App", null, "java/lang/Object", arrayOf("One", "Two"))
        writer.visitEnd()
        val library = archive("ambiguous-defaults.jar", mapOf(
            "One.class" to type("One", isInterface = true),
            "Two.class" to type("Two", isInterface = true),
            "App.class" to writer.toByteArray(),
        ))
        assertEquals(null, ResourceDeclarations(listOf(library)).inheritedMethods("App"))
    }

    @Test fun `missing superclass and superclass cycles do not establish inherited APIs`() {
        val jar = archive("parents.jar", mapOf(
            "MissingParent.class" to type("MissingParent", parent = "Absent"),
            "Cycle.class" to type("Cycle", parent = "Cycle"),
        ))
        val inventory = ResourceDeclarations(listOf(jar))
        assertEquals(null, inventory.inheritedMethods("MissingParent"))
        assertEquals(null, inventory.inheritedMethods("Cycle"))
        assertEquals(setOf("Cycle"), inventory.initialization()["Cycle"])
    }

    @Test fun `static interface methods instance fields and shadowed ancestor fields are not inherited entries`() {
        val writer = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "StaticApi", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "staticValue", "()I", null, null).visitEnd()
            visitEnd()
        }
        val instance = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Instance", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd()
            visitEnd()
        }
        val jar = archive("visibility.jar", mapOf(
            "StaticApi.class" to writer.toByteArray(), "Instance.class" to instance.toByteArray(),
            "Parent.class" to type("Parent", fields = listOf("VALUE")),
            "Child.class" to type("Child", parent = "Parent", fields = listOf("VALUE")),
            "App.class" to type("App", interfaces = arrayOf("StaticApi")),
            "java/lang/Object.class" to type("java/lang/Object", parent = null),
        ))
        val inventory = ResourceDeclarations(listOf(jar))
        assertEquals(emptySet(), inventory.inheritedMethods("App"))
        assertEquals(emptySet(), inventory.entryInitializers("Instance"))
        assertEquals(setOf("Child"), inventory.entryInitializers("Child"))
        assertEquals(null, inventory.fieldOwner("Child.ABSENT:I"))
    }

    private fun archive(name: String, entries: Map<String, ByteArray>): Path {
        val path = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/"))
            zip.closeEntry()
            entries.forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }

    private fun type(name: String, parent: String? = "java/lang/Object", interfaces: Array<String> = emptyArray(),
        isInterface: Boolean = false, fields: List<String> = emptyList(), hasDefault: Boolean = true): ByteArray {
        val writer = ClassWriter(0)
        val access = Opcodes.ACC_PUBLIC or if (isInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else 0
        writer.visit(Opcodes.V17, access, name, null, parent, interfaces)
        fields.forEach { writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, it, "I", null, null).visitEnd() }
        val methodAccess = Opcodes.ACC_PUBLIC or if (hasDefault) 0 else Opcodes.ACC_ABSTRACT
        writer.visitMethod(methodAccess, "value", "()I", null, null).apply {
            if (hasDefault) {
                visitCode()
                visitInsn(Opcodes.ICONST_0)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 1)
            }
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
