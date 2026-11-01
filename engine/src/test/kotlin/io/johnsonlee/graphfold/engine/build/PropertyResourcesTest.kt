package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PropertyResourcesTest {
    @TempDir lateinit var temporary: Path

    @Test fun `properties key edits preserve independent resource and copied archive identities`() {
        val root = Files.createDirectories(temporary.resolve("resources"))
        val file = root.resolve("messages.properties")
        val artifact = temporary.resolve("app.jar")
        fun capture(text: String): ProductionInventory {
            Files.writeString(file, text)
            java.util.zip.ZipOutputStream(Files.newOutputStream(artifact)).use { zip ->
                listOf("messages.properties").forEach {
                    zip.putNextEntry(java.util.zip.ZipEntry(it)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return captureProductionInventory(temporary.resolve("classes"), listOf(root), listOf(root, artifact), listOf(artifact))
        }
        val base = capture("old=Hello\n")
        val head = capture("old=Hello\nnew=Bonjour\n")
        assertNotEquals(base.nonClassEntries, head.nonClassEntries)
        assertEquals(base.resourceIndependentEntries, head.resourceIndependentEntries)
        assertEquals(mapOf("old" to "Hello", "new" to "Bonjour"), head.resourceEntries["messages.properties"])
        assertTrue(head.resourceConsumersKnown)
    }

    @Test fun `invalid encoding malformed escapes and conflicting duplicate resources stay opaque`() {
        val first = Files.createDirectories(temporary.resolve("first"))
        val second = Files.createDirectories(temporary.resolve("second"))
        Files.writeString(first.resolve("messages.properties"), "key=one")
        Files.writeString(second.resolve("messages.properties"), "key=two")
        Files.writeString(first.resolve("invalid.properties"), "key=\\uZZZZ")
        Files.writeString(first.resolve("utf8.properties"), "key=你好")
        Files.writeString(first.resolve("text.txt"), "key=one")
        Files.createDirectories(first.resolve("folder.properties"))
        val inventory = captureProductionInventory(temporary.resolve("classes"), listOf(first, second, temporary.resolve("absent")),
            emptyList(), emptyList())
        assertTrue(inventory.resourceEntries.isEmpty())
        assertEquals(inventory.nonClassEntries, inventory.resourceIndependentEntries)
        Files.writeString(second.resolve("messages.properties"), "key=one")
        assertEquals(mapOf("key" to "one"), PropertyResources(listOf(first, second)).values["messages.properties"])
    }

    @Test fun `external runtime consumers and unmatched copies prevent assuming resource isolation`() {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "key=one")
        val dependency = Files.createDirectories(temporary.resolve("dependency"))
        Files.createDirectories(dependency.resolve("library"))
        Files.writeString(dependency.resolve("library/Consumer.class"), "external")
        Files.writeString(dependency.resolve("messages.properties"), "key=other")
        val inventory = captureProductionInventory(temporary.resolve("classes"), listOf(resources), listOf(dependency), emptyList())
        assertFalse(inventory.resourceConsumersKnown)
        assertEquals(inventory.nonClassEntries["dependency/0"], inventory.resourceIndependentEntries["dependency/0"])
        assertEquals("raw", PropertyResources(listOf(resources)).identity("different.properties", "raw"))
    }
    @Test fun `Kotlin namespace and artifact only classes do not count as trusted resource consumers`() {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "key=value")
        val dependency = Files.createDirectories(temporary.resolve("dependency/kotlin"))
        Files.writeString(dependency.resolve("Evil.class"), "unmodeled")
        val classes = temporary.resolve("classes")
        assertFalse(captureProductionInventory(classes, listOf(resources), listOf(dependency.parent), emptyList()).resourceConsumersKnown)
        val artifact = temporary.resolve("extra.jar")
        java.util.zip.ZipOutputStream(Files.newOutputStream(artifact)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("library/Reader.class"))
            zip.write("unmodeled".toByteArray())
            zip.closeEntry()
        }
        assertFalse(captureProductionInventory(classes, listOf(resources), emptyList(), listOf(artifact)).resourceConsumersKnown)
        Files.writeString(resources.resolve("Unknown.class"), "unmodeled")
        assertFalse(captureProductionInventory(classes, listOf(resources), emptyList(), emptyList()).resourceConsumersKnown)
    }

    @Test fun `custom bundle subclasses and malformed application classes keep resource consumption unknown`() {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "key=value")
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val file = classes.resolve("Custom.class")
        fun capture() = captureProductionInventory(classes, listOf(resources), emptyList(), emptyList())
        Files.writeString(file, "malformed")
        assertFalse(capture().resourceConsumersKnown)
        listOf("java/util/ResourceBundle", "java/util/ListResourceBundle", "java/util/PropertyResourceBundle",
            "java/util/ResourceBundle\$Control", "java/lang/Object").forEach { parent ->
            val writer = org.objectweb.asm.ClassWriter(0)
            writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Custom", null, parent, null)
            writer.visitEnd()
            Files.write(file, writer.toByteArray())
            assertEquals(parent == "java/lang/Object", capture().resourceConsumersKnown, parent)
        }
        Files.createDirectories(classes.resolve("directory.class"))
        Files.writeString(classes.resolve("other.txt"), "not bytecode")
        assertTrue(capture().resourceConsumersKnown)
    }

    @Test fun `archive layout prefixes are not assumed to be runtime classloader mappings`() {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        val file = resources.resolve("messages.properties")
        val artifact = temporary.resolve("app.jar")
        fun capture(text: String): ProductionInventory {
            Files.writeString(file, text)
            java.util.zip.ZipOutputStream(Files.newOutputStream(artifact)).use { zip ->
                listOf("BOOT-INF/classes/messages.properties", "WEB-INF/classes/messages.properties").forEach {
                    zip.putNextEntry(java.util.zip.ZipEntry(it)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return captureProductionInventory(temporary.resolve("classes"), listOf(resources), listOf(artifact), listOf(artifact))
        }
        val base = capture("key=before")
        val head = capture("key=after")
        assertNotEquals(base.resourceIndependentEntries, head.resourceIndependentEntries)
        listOf("BOOT-INF/classes/messages.properties", "WEB-INF/classes/messages.properties").forEach {
            assertEquals(head.nonClassEntries["artifact/0/$it"], head.resourceIndependentEntries["artifact/0/$it"])
        }
    }

}
