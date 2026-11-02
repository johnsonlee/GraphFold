package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.TypeInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InlineOriginAlignmentIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `one inline definition aligns copies by actual callers after another caller is inserted`() {
        val before = compile("base", "fun first() = create { 1 }; fun second() = create { 2 }")
        val after = compile("head", "fun added() = create { 9 }; fun first() = create { 1 }; fun second() = create { 2 }")
        assertEquals(3, before.origins.size)
        assertEquals(4, after.origins.size)
        assertEquals(1, before.origins.map { it.path to it.syntax.range }.toSet().size)
        assertEquals(1, after.origins.map { it.path to it.syntax.range }.toSet().size)
        val result = alignOrigins(before.origins, after.origins)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        before.origins.forEach { old ->
            val current = after.origins.single { it.emissionContext == old.emissionContext }
            assertEquals(result.baseOwners.getValue(old.owner), result.headOwners.getValue(current.owner))
        }
        val added = after.origins.single { it.emissionContext == "sample.Client.added()Ljava/util/function/IntSupplier;" }
        assertTrue(result.headOwners.getValue(added.owner) !in result.baseOwners.values)
        assertEquals(4, result.headOwners.values.toSet().size)
    }

    @Test
    fun `two copies allocated in the same real caller block alignment despite identical physical origin`() {
        val before = compile("base", "fun first() = listOf(create { 1 }, create { 2 })")
        val after = compile("head", "fun first() = listOf(create { 9 }, create { 1 }, create { 2 })")
        val context = "sample.Client.first()Ljava/util/List;"
        val oldCopies = before.origins.filter { it.emissionContext == context }
        val newCopies = after.origins.filter { it.emissionContext == context }
        assertEquals(2, oldCopies.size)
        assertEquals(3, newCopies.size)
        assertEquals(1, (oldCopies + newCopies).map { it.path to it.syntax.range }.toSet().size)
        val result = alignOrigins(before.origins, after.origins)
        assertTrue(result.diagnostics.any { "ambiguous emitted copies" in it }, result.diagnostics.toString())
        oldCopies.forEach { old -> newCopies.forEach { current ->
            assertNotEquals(result.baseOwners.getValue(old.owner), result.headOwners.getValue(current.owner))
        } }
        assertEquals(oldCopies.size, oldCopies.map { result.baseOwners.getValue(it.owner) }.toSet().size)
        assertEquals(newCopies.size, newCopies.map { result.headOwners.getValue(it.owner) }.toSet().size)
    }

    private fun compile(revision: String, methods: String): OriginCatalog {
        val root = Files.createDirectories(temporary.resolve(revision))
        val sources = Files.createDirectories(root.resolve("src/main/kotlin/sample"))
        Files.writeString(sources.resolve("Factory.kt"), """
            package sample
            inline fun create(crossinline value: () -> Int): java.util.function.IntSupplier =
                object : java.util.function.IntSupplier { override fun getAsInt(): Int = value() }
        """.trimIndent())
        Files.writeString(sources.resolve("Client.kt"), "package sample\nclass Client { $methods }")
        val project = ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("$revision-output"))
        val catalog = readOrigins(project, root)
        assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
        assertActualAllocationContexts(project, catalog.origins)
        return catalog
    }

    private fun assertActualAllocationContexts(project: CompiledProject, origins: List<SourceOrigin>) {
        val nodes = Files.walk(project.classes).use { files ->
            files.filter { it.toString().endsWith(".class") }.map { file ->
                ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
            }.toList()
        }
        origins.forEach { origin ->
            val allocations = nodes.filter { node -> origins.none { it.owner == node.name.replace('/', '.') } }
                .flatMap { node -> node.methods.filter { it.access and Opcodes.ACC_SYNTHETIC == 0 }.flatMap { method ->
                    method.instructions.filterIsInstance<TypeInsnNode>()
                        .filter { it.opcode == Opcodes.NEW && it.desc.replace('/', '.') == origin.owner }
                        .map { "${node.name.replace('/', '.')}.${method.name}${method.desc}" }
                } }.toSet()
            assertTrue(allocations.isNotEmpty(), origin.toString())
            assertEquals(allocations, requireNotNull(origin.emissionContext).split('\n').toSet())
        }
    }
}
