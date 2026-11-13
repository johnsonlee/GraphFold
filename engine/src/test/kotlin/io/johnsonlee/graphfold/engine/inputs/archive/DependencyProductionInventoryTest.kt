package io.johnsonlee.graphfold.engine.inputs.archive

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DependencyProductionInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `ordinary runtime class directories and archives require graph evidence in original order`() {
        val first = dependency(mapOf("external/First.class" to bytecode("external/First")), archive = false)
        val second = dependency(mapOf("external/Second.class" to bytecode("external/Second")), archive = true)
        val inventory = capture(listOf(first, second))
        assertTrue(inventory.resourceConsumersKnown)
        assertEquals(listOf(first, second), inventory.resourceDependencies)
        val reversed = capture(listOf(second, first))
        assertEquals(listOf(second, first), reversed.resourceDependencies)
        assertNotEquals(inventory.nonClassEntries, reversed.nonClassEntries)
        assertNotEquals(inventory.resourceIndependentEntries, reversed.resourceIndependentEntries)
        assertEquals(inventory.entries, inventory.nonClassEntries + mapOf("resource/0/messages.properties" to
            inventory.entries.getValue("resource/0/messages.properties")))
    }

    @Test
    fun `actual Kotlin stdlib including versioned module metadata is eligible for graph analysis`() {
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val inventory = capture(listOf(stdlib))
        assertTrue(inventory.resourceConsumersKnown, inventory.toString())
        assertEquals(listOf(stdlib), inventory.resourceDependencies)
    }

    @ParameterizedTest
    @ValueSource(strings = ["java/util/ResourceBundle", "java/util/ListResourceBundle", "java/util/PropertyResourceBundle",
        "java/util/ResourceBundle\$Control", "java/util/spi/ResourceBundleProvider", "java/util/spi/ResourceBundleControlProvider"])
    fun `custom resource bundle implementations remain unknown even when no caller is visible`(parent: String) {
        val provider = parent.startsWith("java/util/spi/")
        val bytes = bytecode("external/Custom", if (provider) "java/lang/Object" else parent,
            if (provider) arrayOf(parent) else emptyArray())
        listOf(false, true).forEach { archive ->
            val inventory = capture(listOf(dependency(mapOf("external/Custom.class" to bytes), archive)))
            assertFalse(inventory.resourceConsumersKnown, parent)
            assertTrue(inventory.resourceDependencies.isEmpty())
        }
        val classes = Files.createDirectories(temporary.resolve("classes/external"))
        Files.write(classes.resolve("Custom.class"), bytes)
        assertFalse(capture(emptyList()).resourceConsumersKnown)
    }

    @ParameterizedTest
    @ValueSource(strings = ["META-INF/versions/17/external/Reader.class", "BOOT-INF/classes/external/Reader.class",
        "WEB-INF/classes/external/Reader.class", "other/Reader.class", "module-info.class"])
    fun `relocated or versioned runtime code and invalid module descriptors cannot be assumed analyzed`(name: String) {
        val bytes = bytecode("external/Reader")
        val inventory = capture(listOf(dependency(mapOf(name to bytes), archive = true)))
        assertFalse(inventory.resourceConsumersKnown, name)
        assertTrue(inventory.resourceDependencies.isEmpty())
    }

    @Test
    fun `module metadata without providers contains no implicit service activation`() {
        val modules = dependency(mapOf("module-info.class" to module(),
            "META-INF/versions/9/module-info.class" to module()), archive = true)
        val inventory = capture(listOf(modules))
        assertTrue(inventory.resourceConsumersKnown)
        assertTrue(inventory.resourceDependencies.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["java/util/spi/ResourceBundleControlProvider", "example/Service"])
    fun `module providers cannot silently activate factories without implemented service interfaces`(service: String) {
        val bytes = module(service)
        listOf("module-info.class", "META-INF/versions/9/module-info.class").forEach { descriptor ->
            listOf(false, true).forEach { archive ->
                val dependency = dependency(mapOf(descriptor to bytes,
                    "factory/Factory.class" to bytecode("factory/Factory")), archive)
                val inventory = capture(listOf(dependency))
                assertFalse(inventory.resourceConsumersKnown, "$service in $descriptor")
                assertTrue(inventory.resourceDependencies.isEmpty())
            }
        }
        val application = Files.createDirectories(temporary.resolve("classes"))
        Files.write(application.resolve("module-info.class"), bytes)
        assertFalse(capture(emptyList()).resourceConsumersKnown)
        Files.write(application.resolve("module-info.class"), module())
        assertTrue(capture(emptyList()).resourceConsumersKnown)
        Files.write(application.resolve("module-info.class"), byteArrayOf(0))
        assertFalse(capture(emptyList()).resourceConsumersKnown)
    }

    @ParameterizedTest
    @ValueSource(strings = ["META-INF/MANIFEST.MF", "meta-inf/manifest.mf"])
    fun `manifest class paths cannot silently add resource consumers outside the resolved runtime graph`(manifest: String) {
        listOf(false, true).forEach { archive ->
            listOf("", "Class-Path: \n", "Class-Path: hidden.jar\n", "Class-Path: first.jar\n second.jar\n").forEach { attribute ->
                val entries = mapOf("external/Reader.class" to bytecode("external/Reader"),
                    manifest to "Manifest-Version: 1.0\n${attribute}\n".toByteArray())
                val inventory = capture(listOf(dependency(entries, archive)))
                val known = "jar" !in attribute
                assertEquals(known, inventory.resourceConsumersKnown, "archive=$archive, attribute=$attribute")
                assertEquals(known, inventory.resourceDependencies.isNotEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["META-INF/MANIFEST.MF", "meta-inf/manifest.mf"])
    fun `artifact only and resource manifests cannot expand the runtime classpath outside its export`(manifest: String) {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "old=Hello")
        val target = resources.resolve(manifest)
        Files.createDirectories(target.parent)
        listOf("", "Class-Path: \n", "Class-Path: hidden.jar\n", "not a manifest header\n").forEach { attribute ->
            val content = "Manifest-Version: 1.0\n${attribute}\n"
            val artifact = dependency(mapOf(manifest to content.toByteArray(),
                "messages.properties" to "old=Hello".toByteArray()), archive = true)
            val inventory = captureProductionInventory(temporary.resolve("classes"), listOf(resources), emptyList(), listOf(artifact))
            val known = attribute.isEmpty() || attribute == "Class-Path: \n"
            assertEquals(known, inventory.resourceConsumersKnown, "artifact manifest: $attribute")
            assertTrue(inventory.resourceDependencies.isEmpty())
            Files.writeString(target, content)
            val exportedResource = capture(emptyList())
            assertEquals(known, exportedResource.resourceConsumersKnown, "resource manifest: $attribute")
            assertTrue(exportedResource.resourceDependencies.isEmpty())
            assertEquals(exportedResource.entries.getValue("resource/0/$manifest"),
                inventory.entries.getValue("artifact/0/$manifest"), "Scope checks must preserve the manifest byte identity")
            Files.delete(target)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["lib/nested.jar", "lib/nested.zip", "lib/nested.war", "external/Broken.class"])
    fun `nested archives and malformed runtime bytecode remain unknown`(entry: String) {
        listOf(false, true).forEach { archive ->
            val inventory = capture(listOf(dependency(mapOf(entry to "opaque".toByteArray()), archive)))
            assertFalse(inventory.resourceConsumersKnown, entry)
            assertTrue(inventory.resourceDependencies.isEmpty())
        }
    }

    @Test
    fun `matched application copies are excluded but mixed or transformed application archives remain unknown`() {
        val application = bytecode("sample/Example")
        val classes = Files.createDirectories(temporary.resolve("classes/sample"))
        Files.write(classes.resolve("Example.class"), application)
        val same = dependency(mapOf("sample/Example.class" to application), archive = true)
        val matched = capture(listOf(same))
        assertTrue(matched.resourceConsumersKnown)
        assertTrue(matched.resourceDependencies.isEmpty())
        val mixed = dependency(mapOf("sample/Example.class" to application,
            "external/Reader.class" to bytecode("external/Reader")), archive = true)
        assertFalse(capture(listOf(mixed)).resourceConsumersKnown)
        val transformed = dependency(mapOf("sample/Example.class" to bytecode("sample/Example", "java/lang/Number")), archive = true)
        assertFalse(capture(listOf(transformed)).resourceConsumersKnown)
    }

    private fun capture(dependencies: List<Path>): ProductionInventory {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "old=Hello")
        return captureProductionInventory(temporary.resolve("classes"), listOf(resources), dependencies, emptyList())
    }

    private fun dependency(entries: Map<String, ByteArray>, archive: Boolean): Path {
        val root = Files.createTempDirectory(temporary, "dependency-")
        if (archive) {
            val path = root.resolve("library.jar")
            ZipOutputStream(Files.newOutputStream(path)).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            return path
        }
        entries.forEach { (name, bytes) ->
            val file = root.resolve(name)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        return root
    }

    private fun bytecode(name: String, parent: String = "java/lang/Object", interfaces: Array<String> = emptyArray()): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, interfaces)
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `module named class without the module access flag is not trusted as descriptor metadata`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "module-info", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()
        assertFalse(capture(listOf(dependency(mapOf("module-info.class" to bytes), archive = true))).resourceConsumersKnown)
    }

    @Test
    fun `valid packaged manifest cannot erase an earlier unknown runtime consumer`() {
        val runtime = dependency(mapOf("lib/nested.jar" to byteArrayOf(1)), archive = true)
        val artifact = dependency(mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n\n".toByteArray()), archive = true)
        val inventory = captureProductionInventory(temporary.resolve("classes"), emptyList(), listOf(runtime), listOf(artifact))
        assertFalse(inventory.resourceConsumersKnown)
        assertTrue(inventory.entries.containsKey("artifact/0/META-INF/MANIFEST.MF"))
    }

    private fun module(service: String? = null): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null)
        writer.visitModule("sample.module", 0, null).apply {
            visitRequire("java.base", Opcodes.ACC_MANDATED, null)
            visitExport("factory", 0)
            visitUse("java/util/spi/ResourceBundleControlProvider")
            service?.let { visitProvide(it, "factory/Factory") }
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
