package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StaticClasspathOrderCacheTest {
    @TempDir lateinit var root: Path
    private val cache = DependencyInputCache()
    private var sequence = 0

    @Test fun `cache preserves first definition when explicit order reverses`() {
        val first = library("first", 1)
        val second = library("second", 2)
        val forward = normalize(listOf(first, second), StaticClassOrder.EXPLICIT_ORDER)
        val reversed = normalize(listOf(second, first), StaticClassOrder.EXPLICIT_ORDER)
        assertContentEquals(bytes(1), readWarArchive(forward.first()).getValue("Value.class"))
        assertContentEquals(bytes(2), readWarArchive(reversed.first()).getValue("Value.class"))
        assertFalse("Value.class" in readWarArchive(forward.last()))
        assertFalse("Value.class" in readWarArchive(reversed.last()))
        assertEquals(forward, normalize(listOf(first, second), StaticClassOrder.EXPLICIT_ORDER))
    }

    @Test fun `explicit-order cache entry cannot bypass an unambiguous-policy obligation`() {
        val dependencies = listOf(library("first", 1), library("second", 2))
        normalize(dependencies, StaticClassOrder.EXPLICIT_ORDER)
        assertFailsWith<InputFailure> { normalize(dependencies, StaticClassOrder.UNAMBIGUOUS) }
        assertContentEquals(bytes(1), readWarArchive(normalize(dependencies, StaticClassOrder.EXPLICIT_ORDER).first())
            .getValue("Value.class"))
    }

    @Test fun `application-first cache selection still distinguishes explicit and unambiguous policies`() {
        val dependency = library("dependency", 2)
        val application = Files.createDirectory(root.resolve("application"))
        Files.write(application.resolve("Value.class"), bytes(1))
        val selected = normalize(listOf(dependency), StaticClassOrder.EXPLICIT_ORDER, application)
        assertFalse("Value.class" in readWarArchive(selected.single()))
        assertFailsWith<InputFailure> { normalize(listOf(dependency), StaticClassOrder.UNAMBIGUOUS, application) }
    }

    private fun normalize(inputs: List<Path>, policy: StaticClassOrder, application: Path? = null): List<Path> =
        normalizeDependencyInputs(inputs, root.resolve("normalized-${sequence++}"), applicationClasses = application,
            cache = cache, classOrder = policy)

    private fun library(name: String, value: Int): Path = projectionArchive(root.resolve("$name.jar"),
        mapOf("Value.class" to bytes(value)))

    private fun bytes(value: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Value", null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VALUE", "I", null, value).visitEnd()
        visitEnd()
    }.toByteArray()
}
