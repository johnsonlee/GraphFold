package io.johnsonlee.graphfold.engine.bytecode.asm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.metadata.KmClass
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StructuralClassInventoryTest {
    @TempDir lateinit var temporary: Path
    private var sequence = 0

    @Test
    fun `Kotlin metadata nesting remains evidence when JVM inner class rows are identical`() {
        val before = inventory(listOf("Nested"))
        val after = inventory(listOf("Nested", "OnlyInMetadata"))
        val oldShape = before.structuralClassShapes.getValue(OWNER)
        val newShape = after.structuralClassShapes.getValue(OWNER)
        assertEquals(oldShape.core, newShape.core)
        assertNotEquals(before.classShapes.getValue(OWNER), after.classShapes.getValue(OWNER))
        assertNotEquals(oldShape.nestedTypes, newShape.nestedTypes)
        assertTrue(oldShape.nestedTypes.none { it.first == "$OWNER\$OnlyInMetadata" })
        assertTrue(newShape.nestedTypes.any { it.first == "$OWNER\$OnlyInMetadata" })
        assertTrue(before.diagnostics.isEmpty(), before.diagnostics.toString())
        assertTrue(after.diagnostics.isEmpty(), after.diagnostics.toString())
    }

    @Test
    fun `only the canonical external Lookup inner class entry is excluded`() {
        val absent = inventory().structuralClassShapes.getValue(OWNER)
        val canonical = inventory(lookup = LookupRow()).structuralClassShapes.getValue(OWNER)
        assertEquals(absent, canonical)
        val malformed = listOf(
            LookupRow(outer = "sample/Other"),
            LookupRow(inner = "Different"),
            LookupRow(access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC),
        )
        malformed.forEach { row ->
            val shape = inventory(lookup = row).structuralClassShapes.getValue(OWNER)
            assertEquals(absent.core, shape.core)
            assertNotEquals(absent.nestedTypes, shape.nestedTypes, row.toString())
            assertTrue(shape.nestedTypes.any { it.first == "java.lang.invoke.MethodHandles\$Lookup" }, row.toString())
        }
    }

    @Test
    fun `invisible metadata retains its nested ownership records`() {
        val shape = inventory(listOf("Nested"), metadataVisible = false).structuralClassShapes.getValue(OWNER)
        assertTrue(shape.nestedTypes.any { it.first == "$OWNER\$Nested" && it.second.startsWith("kotlin:") })
    }

    @Test
    fun `ordinary invisible annotations retain literal evidence without inventing nested owners`() {
        val absent = inventory().structuralClassShapes.getValue(OWNER)
        val annotated = inventory(marker = "Lsample/Marker;").structuralClassShapes.getValue(OWNER)
        assertTrue(annotated.nestedTypes.isEmpty())
        assertNotEquals(absent.core, annotated.core)
    }

    private fun inventory(
        nested: List<String>? = null, lookup: LookupRow? = null, metadataVisible: Boolean = true, marker: String? = null,
    ): BytecodeInventory {
        val directory = Files.createDirectory(temporary.resolve("classes-${sequence++}"))
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Example", null, "java/lang/Object", null)
        if (nested != null) {
            writer.visitInnerClass("sample/Example\$Nested", "sample/Example", "Nested", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
            writeMetadata(writer, nested, metadataVisible)
        }
        if (marker != null) writer.visitAnnotation(marker, false).visitEnd()
        if (lookup != null) {
            writer.visitInnerClass("java/lang/invoke/MethodHandles\$Lookup", lookup.outer, lookup.inner, lookup.access)
        }
        writer.visitEnd()
        val file = directory.resolve("sample/Example.class")
        Files.createDirectories(file.parent)
        Files.write(file, writer.toByteArray())
        return BytecodeInventory().also { it.read(directory) }
    }

    private fun writeMetadata(writer: ClassWriter, nested: List<String>, visible: Boolean) {
        val description = KmClass()
        description.name = "sample/Example"
        description.nestedClasses.addAll(nested)
        val metadata = KotlinClassMetadata.Class(description, JvmMetadataVersion(2, 1, 0), 0).write()
        val annotation = writer.visitAnnotation("Lkotlin/Metadata;", visible)
        annotation.visit("k", metadata.kind)
        annotation.visit("mv", metadata.metadataVersion)
        for ((name, values) in listOf("d1" to metadata.data1, "d2" to metadata.data2)) {
            val array = annotation.visitArray(name)
            values.forEach { array.visit(null, it) }
            array.visitEnd()
        }
        annotation.visit("xi", metadata.extraInt)
        annotation.visitEnd()
    }

    private data class LookupRow(
        val outer: String = "java/lang/invoke/MethodHandles", val inner: String = "Lookup",
        val access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
    )

    private companion object { const val OWNER = "sample.Example" }
}
