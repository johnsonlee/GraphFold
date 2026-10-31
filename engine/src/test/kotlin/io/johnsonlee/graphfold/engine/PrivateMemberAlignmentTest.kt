package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.FieldSnapshot
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateMemberAlignmentTest {
    @Test
    fun `private helper rename preserves references but a changed body does not`() {
        val old = helper("Example.before()I")
        val new = helper("Example.after()I")
        val base = snapshot(root(old.identity), old)
        val head = snapshot(root(new.identity), new)
        assertEquals(mapOf(new.identity to old.identity), PrivateMemberAlignment(base, head).names)
        assertTrue(SnapshotComparison(base, head).differences(obligation(root().identity)).isEmpty())
        assertTrue(SnapshotComparison(base, head).differences(obligation(new.identity)).isEmpty())
        val changed = head.copy(methods = head.methods + (new.identity to new.copy(referenceCanonical = "return 2")))
        assertTrue(SnapshotComparison(base, changed).differences(obligation(root().identity)).isNotEmpty())
    }

    @Test
    fun `member normalization never rewrites a matching name inside a string literal`() {
        val old = helper("Example.before()I")
        val new = helper("Example.after()I")
        val base = snapshot(root(old.identity).copy(referenceCanonical = "string Example.before()I; call @member0"), old)
        val head = snapshot(root(new.identity).copy(referenceCanonical = "string Example.after()I; call @member0"), new)
        assertTrue(SnapshotComparison(base, head).differences(obligation(root().identity)).isNotEmpty())
    }

    @Test
    fun `private field rename is unique and preserves metadata constant and references`() {
        val old = FieldSnapshot("Example.old:I", "private int", "1", true)
        val new = old.copy(identity = "Example.new:I")
        val base = snapshot(root(old.identity)).copy(fields = mapOf(old.identity to old))
        val head = snapshot(root(new.identity)).copy(fields = mapOf(new.identity to new))
        val change = ProofObligation(old.identity, SymbolKind.FIELD, "Example", emptyList())
        assertTrue(SnapshotComparison(base, head).differences(change).isEmpty())
        val different = head.copy(fields = mapOf(new.identity to new.copy(constant = "2")))
        assertFalse(PrivateMemberAlignment(base, different).names.containsKey(new.identity))
        assertTrue(SnapshotComparison(base, different).differences(change).isNotEmpty())
    }

    @Test
    fun `ambiguous or externally named members never receive an arbitrary rename mapping`() {
        val before = helper("Example.before()I")
        val a = helper("Example.a()I")
        val b = helper("Example.b()I")
        assertTrue(PrivateMemberAlignment(snapshot(before), snapshot(a, b)).names.isEmpty())
        assertTrue(PrivateMemberAlignment(snapshot(a, b), snapshot(before)).names.isEmpty())
        assertTrue(PrivateMemberAlignment(snapshot(before.copy(modifiers = setOf("PUBLIC"))), snapshot(a)).names.isEmpty())
        assertTrue(PrivateMemberAlignment(snapshot(before), snapshot(a.copy(identity = "Other.a()I"))).names.isEmpty())
        assertTrue(PrivateMemberAlignment(snapshot(before), snapshot(a.copy(identity = "Example.a(I)I"))).names.isEmpty())
        val field = FieldSnapshot("Example.old:I", "private", "1", false)
        val base = snapshot().copy(fields = mapOf(field.identity to field))
        val head = snapshot().copy(fields = mapOf("Example.new:I" to field.copy(identity = "Example.new:I")))
        assertTrue(PrivateMemberAlignment(base, head).names.isEmpty())
    }

    @Test
    fun `renamed helper chains and self recursion resolve without flattening call structure`() {
        val leaf = helper("Example.leaf()I")
        val caller = helper("Example.caller()I").copy(references = listOf(leaf.identity))
        val newLeaf = leaf.copy(identity = "Example.newLeaf()I")
        val newCaller = caller.copy(identity = "Example.newCaller()I", references = listOf(newLeaf.identity))
        assertEquals(2, PrivateMemberAlignment(snapshot(caller, leaf), snapshot(newCaller, newLeaf)).names.size)
        val recursive = leaf.copy(references = listOf(leaf.identity))
        val renamed = newLeaf.copy(references = listOf(newLeaf.identity))
        assertEquals(mapOf(renamed.identity to recursive.identity),
            PrivateMemberAlignment(snapshot(recursive), snapshot(renamed)).names)
    }

    @Test
    fun `unused field metadata is omitted but initializers and used fields remain observable`() {
        val field = FieldSnapshot("Example.unused:I", "private", "1", true)
        val before = snapshot(root()).copy(fields = mapOf(field.identity to field), ignoredFields = setOf(field.identity))
        val after = before.copy(fields = mapOf(field.identity to field.copy(constant = "2")))
        val obligation = ProofObligation(field.identity, SymbolKind.FIELD, "Example", emptyList())
        assertTrue(SnapshotComparison(before, after).differences(obligation).isEmpty())
        assertTrue(SnapshotComparison(before.copy(ignoredFields = emptySet()),
            after.copy(ignoredFields = emptySet())).differences(obligation).isNotEmpty())
    }

    @Test
    fun `uncalled added method is unreachable even in a reachable owner`() {
        val base = snapshot(root())
        val added = helper("Example.added()I").copy(modifiers = setOf("PUBLIC"))
        val head = snapshot(root(), added)
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.isUnreachable(obligation(added.identity)))
        assertTrue(comparison.differences(obligation(added.identity)).isEmpty())
    }

    @Test
    fun `anonymous callbacks require an allocation while public entry owners retain static initialization`() {
        val callback = helper("Example\$1.run()I").copy(modifiers = setOf("PUBLIC"))
        val base = snapshot(root(), callback).copy(entryClasses = setOf("Example"))
        val head = base.copy(methods = base.methods + (callback.identity to callback.copy(referenceCanonical = "changed")))
        assertTrue(SnapshotComparison(base, head).differences(obligation(root().identity)).isEmpty())
        val initializer = helper("Example.<clinit>()V").copy(modifiers = setOf("STATIC"))
        val before = snapshot(root(), initializer)
        val after = snapshot(root(), initializer.copy(referenceCanonical = "observable side effect"))
        assertTrue(SnapshotComparison(before, after).differences(obligation(root().identity)).isNotEmpty())
    }

    @Test
    fun `new framework entry cannot be classified as uncalled`() {
        val base = snapshot(root())
        val added = helper("Example.handler()I").copy(modifiers = setOf("PUBLIC"))
        val head = snapshot(root(), added).copy(externalEntries = setOf(added.identity))
        val comparison = SnapshotComparison(base, head)
        assertFalse(comparison.isUnreachable(obligation(added.identity)))
        assertTrue(comparison.differences(obligation(added.identity)).isNotEmpty())
    }

    @Test
    fun `private fields moved across owners or with changed contracts cannot be aligned`() {
        val old = FieldSnapshot("Example.old:I", "private int", "1", true)
        val before = snapshot().copy(fields = mapOf(old.identity to old))
        listOf(
            old.copy(identity = "Other.new:I"),
            old.copy(identity = "Example.new:I", declaration = "private volatile int"),
        ).forEach { changed ->
            val after = snapshot().copy(fields = mapOf(changed.identity to changed))
            assertTrue(PrivateMemberAlignment(before, after).names.isEmpty(), changed.toString())
        }
    }

    @Test
    fun `private method annotation contract changes prevent a body-identical rename proof`() {
        val old = helper("Example.before()I")
        val new = helper("Example.after()I")
        val before = snapshot(root(old.identity), old).copy(declarations =
            mapOf("Example" to "class", old.identity to "private @First int"))
        val after = snapshot(root(new.identity), new).copy(declarations =
            mapOf("Example" to "class", new.identity to "private @Second int"))
        assertTrue(PrivateMemberAlignment(before, after).names.isEmpty())
        assertTrue(SnapshotComparison(before, after).differences(obligation(root().identity)).isNotEmpty())
    }

    @Test
    fun `ambiguous private field merge cannot pick one of two indistinguishable originals`() {
        val first = FieldSnapshot("Example.first:I", "private int", "1", true)
        val second = first.copy(identity = "Example.second:I")
        val merged = first.copy(identity = "Example.merged:I")
        val before = snapshot().copy(fields = mapOf(first.identity to first, second.identity to second))
        val after = snapshot().copy(fields = mapOf(merged.identity to merged))
        assertTrue(PrivateMemberAlignment(before, after).names.isEmpty())
    }

    @Test
    fun `fixed point pairs a reversed helper chain but leaves mutual recursion and ambiguous leaves unmatched`() {
        val count = 24
        val before = (0 until count).map { index ->
            helper("Example.old$index()I").copy(references =
                if (index + 1 == count) emptyList() else listOf("Example.old${index + 1}()I"))
        }
        val after = (0 until count).map { index ->
            helper("Example.new$index()I").copy(references =
                if (index + 1 == count) emptyList() else listOf("Example.new${index + 1}()I"))
        }
        val oldCycle = listOf(helper("Cycle.a()I").copy(references = listOf("Cycle.b()I")),
            helper("Cycle.b()I").copy(references = listOf("Cycle.a()I")))
        val newCycle = listOf(helper("Cycle.x()I").copy(references = listOf("Cycle.y()I")),
            helper("Cycle.y()I").copy(references = listOf("Cycle.x()I")))
        val oldAmbiguous = listOf(helper("Ambiguous.a()I"), helper("Ambiguous.b()I"))
        val newAmbiguous = listOf(helper("Ambiguous.x()I"), helper("Ambiguous.y()I"))
        val alignment = PrivateMemberAlignment(snapshot(*(before + oldCycle + oldAmbiguous).toTypedArray()),
            snapshot(*(after.reversed() + newCycle + newAmbiguous).toTypedArray()))
        assertEquals(before.indices.associate { after[it].identity to before[it].identity }, alignment.names)
    }

    private fun helper(identity: String) = MethodSnapshot(identity, "body", emptySet(), setOf("PRIVATE"), emptySet())

    private fun root(reference: String? = null): MethodSnapshot = MethodSnapshot(
        "Example.run()I", "body", if (reference == null || '(' !in reference) emptySet() else setOf(reference),
        setOf("PUBLIC"), emptySet(), "root @member0", listOfNotNull(reference),
    )

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(
        methods.associateBy { it.identity }, mapOf("Example" to "class"), emptyList(), emptyList(),
        classShapes = mapOf("Example" to "shape"),
    )

    private fun obligation(identity: String) = ProofObligation(
        identity, SymbolKind.METHOD, identity.substringBefore('(').substringBeforeLast('.'), emptyList(),
    )
}
