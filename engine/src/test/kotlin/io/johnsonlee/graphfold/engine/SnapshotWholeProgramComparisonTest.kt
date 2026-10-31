package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.FieldSnapshot
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotWholeProgramComparisonTest {
    @Test
    fun `whole program proof finds reachable generated method and class changes once in sorted order`() {
        val root = method("Example.run()I", calls = setOf("Generated.value()I"))
        val generated = method("Generated.value()I", "return 1")
        val other = method("Other.run()I", "return 1")
        val base = snapshot(root, generated, other)
        val head = snapshot(root, generated.copy(canonical = "return 2", referenceCanonical = "return 2"),
            other.copy(canonical = "return 2", referenceCanonical = "return 2"))
            .let { it.copy(declarations = it.declarations + ("Generated" to "changed generated metadata")) }
        assertEquals(listOf("Generated", "Generated.value()I"), SnapshotComparison(base, head).wholeProgramDifferences())
    }

    @Test
    fun `whole program proof compares observable field shape without treating method declarations as owners`() {
        val root = method("Example.run()I")
        val field = FieldSnapshot("Example.LIMIT:I", "public static final int", "1", renameSafe = false)
        val base = snapshot(root).let {
            it.copy(declarations = it.declarations + (root.identity to "public int"),
                classShapes = mapOf("Example" to "public class"), fields = mapOf(field.identity to field))
        }
        val head = base.copy(fields = mapOf(field.identity to field.copy(constant = "2")))
        assertEquals(listOf("Example"), SnapshotComparison(base, head).wholeProgramDifferences())
        assertTrue(SnapshotComparison(base, base).wholeProgramDifferences().isEmpty())
    }

    @Test
    fun `whole program proof retains removed reachable methods and class owners from base`() {
        val root = method("Example.run()I", "call Generated", setOf("Generated.value()I"))
        val base = snapshot(root, method("Generated.value()I", "return 1"))
        val head = snapshot(method(root.identity, "return 0"))
        assertEquals(listOf("Example.run()I", "Generated", "Generated.value()I"),
            SnapshotComparison(base, head).wholeProgramDifferences())
    }

    @Test
    fun `whole program proof ignores new unreachable classes including constructors and static initialization`() {
        val root = method("Example.run()I")
        val unused = method("Example.unused()I", "return 1", isPrivate = true)
        val base = snapshot(root, unused)
        val head = snapshot(root, unused.copy(canonical = "return 2", referenceCanonical = "return 2"),
            method("Unused.<init>()V"), method("Unused.<clinit>()V", "side effect"), method("Unused.run()I", "return 2"))
        assertTrue(SnapshotComparison(base, head).wholeProgramDifferences().isEmpty())
        assertTrue(SnapshotComparison(head, base).wholeProgramDifferences().isEmpty())
    }

    @Test
    fun `whole program proof includes added generated methods reached through surviving entries`() {
        val base = snapshot(method("Example.run()I", "return 1"))
        val head = snapshot(method("Example.run()I", "call Generated", setOf("Generated.value()I")),
            method("Generated.value()I", "return 2"))
        assertEquals(listOf("Example.run()I", "Generated", "Generated.value()I"),
            SnapshotComparison(base, head).wholeProgramDifferences())
    }

    @Test
    fun `whole program proof includes external generated callbacks despite no ordinary caller`() {
        val root = method("Example.run()I")
        val callback = method("Generated.onEvent()V", "old")
        val base = snapshot(root, callback).copy(externalEntries = setOf(callback.identity))
        val head = snapshot(root, callback.copy(canonical = "new", referenceCanonical = "new"))
            .copy(externalEntries = setOf(callback.identity))
        assertEquals(listOf(callback.identity), SnapshotComparison(base, head).wholeProgramDifferences())
    }

    private fun method(identity: String, body: String = "body", calls: Set<String> = emptySet(), isPrivate: Boolean = false) =
        MethodSnapshot(identity, body, calls, setOf(if (isPrivate) MethodModifier.PRIVATE else MethodModifier.PUBLIC), emptySet())

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(
        methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" },
        emptyList(), emptyList(), entryClasses = setOf("Example"),
    )
}
