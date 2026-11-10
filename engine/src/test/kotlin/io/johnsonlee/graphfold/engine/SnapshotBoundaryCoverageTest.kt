package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.graphite.ResourceRead
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotBoundaryCoverageTest {
    @Test fun `legacy entry inventory does not invent newly added overrides as entries`() {
        val root = method("Root.run()V")
        val added = method("Root.callback()V")
        val base = snapshot(root)
        val head = snapshot(root, added).copy(overrides = setOf(added.identity))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.isUnreachable(obligation(added.identity)))
        assertTrue(comparison.differences(obligation(added.identity)).isEmpty())
    }

    @Test fun `reference operands and head-only or removed callees preserve all difference identities`() {
        val root = method("Root.run()V", calls = setOf("Helper.first()V"))
        val before = method("Helper.first()V", hidden = true).copy(canonical = "before", referenceCanonical = "before")
        val after = method("Helper.second()V", hidden = true).copy(canonical = "after", referenceCanonical = "after")
        val base = snapshot(root, before)
        val head = snapshot(root.copy(calls = setOf(after.identity), references = listOf(after.identity)), after)
        val comparison = SnapshotComparison(base, head)
        assertEquals(listOf(before.identity, after.identity, root.identity).sorted(), comparison.differences(obligation(root.identity)))
        assertTrue(comparison.analysisGaps(obligation(after.identity)).isEmpty())
        assertTrue(SnapshotComparison(head, base).analysisGaps(obligation(before.identity)).isEmpty())
    }

    @Test fun `resource entry effects retain base head and external scopes without an explicit class list`() {
        val root = method("Root.run()V")
        val baseRead = ResourceRead("old", "key")
        val headRead = ResourceRead("new", "key")
        val externalRead = ResourceRead("external", "key")
        val base = snapshot(root).copy(resourceEntryReads = mapOf("Root" to listOf(baseRead)))
        val head = snapshot(root).copy(externalEntries = setOf("External.run()V"),
            resourceEntryReads = mapOf("Root" to listOf(headRead), "External" to listOf(externalRead)))
        assertEquals(listOf(baseRead, headRead, externalRead), SnapshotComparison(base, head).resourceEntryReads)
        assertEquals(listOf(headRead, baseRead, externalRead), SnapshotComparison(head, base).resourceEntryReads)
    }

    @Test fun `class and field roots are unreachable only when no relevant runtime method remains`() {
        val dead = method("Dead.run()V", hidden = true)
        val base = snapshot(dead).copy(entryClasses = emptySet())
        val comparison = SnapshotComparison(base, base)
        assertTrue(comparison.isUnreachable(obligation("Dead", SymbolKind.CLASS)))
        assertTrue(comparison.isUnreachable(obligation("Dead.value:I", SymbolKind.FIELD)))
        val reachable = snapshot(method("Dead.run()V"))
        val live = SnapshotComparison(reachable, reachable)
        assertFalse(live.isUnreachable(obligation("Dead", SymbolKind.CLASS)))
        assertFalse(live.isUnreachable(obligation("Dead.value:I", SymbolKind.FIELD)))
    }

    @Test fun `nested owner cycles terminate and preserve reachable nested implementation differences`() {
        val root = method("Root.run()V")
        val nested = method("Nested.run()V")
        val base = snapshot(root, nested).copy(enclosingClasses = mapOf("Nested" to "Root", "Root" to "Nested"))
        val head = base.copy(methods = base.methods + (nested.identity to nested.copy(referenceCanonical = "changed")))
        val comparison = SnapshotComparison(base, head)
        assertFalse(comparison.isUnreachable(obligation("Root", SymbolKind.CLASS)))
        assertEquals(listOf(nested.identity), comparison.differences(obligation("Root", SymbolKind.CLASS)))
    }

    @Test fun `a source owner mismatch does not discard the observed method identity`() {
        val root = method("Root.run()V")
        val base = snapshot(root).copy(declarations = mapOf("Root" to "class", "SourceOwner" to "source class"))
        val head = base.copy(methods = mapOf(root.identity to root.copy(canonical = "changed", referenceCanonical = "changed")))
        val source = ProofObligation(root.identity, SymbolKind.METHOD, "SourceOwner", emptyList())
        val comparison = SnapshotComparison(base, head)
        assertFalse(comparison.isUnreachable(source))
        assertEquals(listOf(root.identity), comparison.differences(source))
    }

    private fun obligation(identity: String, kind: SymbolKind = SymbolKind.METHOD): ProofObligation {
        val owner = if (kind == SymbolKind.CLASS) identity else identity.substringBefore('(').substringBefore(':').substringBeforeLast('.')
        return ProofObligation(identity, kind, owner, emptyList())
    }

    private fun method(identity: String, calls: Set<String> = emptySet(), hidden: Boolean = false) =
        MethodSnapshot(identity, "body", calls, setOf(if (hidden) MethodModifier.PRIVATE else MethodModifier.PUBLIC), emptySet())

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" }, emptyList(), emptyList())
}
