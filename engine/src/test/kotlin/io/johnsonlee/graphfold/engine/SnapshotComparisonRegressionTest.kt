package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.FieldSnapshot
import io.johnsonlee.graphfold.engine.graphite.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotComparisonRegressionTest {
    @Test
    fun `private bridge and target renames preserve head-only diagnostic owner and callee`() {
        fun revision(bridge: String, target: String): ProgramSnapshot {
            val entry = method("Example.run()V", calls = setOf(bridge), references = listOf(bridge))
            val forwarding = method(bridge, "bridge", setOf(target, "Opaque.read()V"), listOf(target), isPrivate = true)
            val leaf = method(target, "leaf", isPrivate = true)
            return snapshot(entry, forwarding, leaf)
        }
        val base = revision("Example.beforeBridge()V", "Example.beforeTarget()V")
        val head = revision("Example.afterBridge()V", "Example.afterTarget()V").copy(methodDiagnostics = mapOf(
            "Example.afterBridge()V" to listOf(
                MethodAnalysisGap("renamed application call", "Example.afterTarget()V"),
                MethodAnalysisGap("unchanged opaque call", "Opaque.read()V"),
                MethodAnalysisGap("body without a resolved callee"),
            ),
        ))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.wholeProgramDifferences().isEmpty())
        val expected = listOf("body without a resolved callee", "renamed application call", "unchanged opaque call")
        assertEquals(expected, comparison.analysisGaps(obligation("Example.run()V")))
        assertEquals(expected, comparison.analysisGaps(obligation("Example.afterBridge()V")))
        assertEquals(expected, comparison.analysisGaps())
    }

    @Test
    fun `head-only gap cannot borrow an opaque invocation from the base revision`() {
        val root = method("Example.run()V", calls = setOf("Opaque.read()V"))
        val base = snapshot(root)
        val head = snapshot(root.copy(calls = emptySet())).copy(methodDiagnostics = mapOf(
            root.identity to listOf(MethodAnalysisGap("head removed the call", "Opaque.read()V", globalObservation = true)),
        ))
        assertTrue(SnapshotComparison(base, head).analysisGaps(obligation(root.identity)).isEmpty())
    }

    @Test
    fun `bounded consumer only invalidates owners it can observe and only while its call survives`() {
        val root = method("Example.run()V")
        val hidden = method("Payload.hidden()V", isPrivate = true)
        val reader = method("Consumer.inspect()V", calls = setOf("Opaque.read()V"))
        val base = snapshot(root, hidden, reader).copy(entryClasses = setOf("Example", "Consumer"),
            methodDiagnostics = mapOf(reader.identity to listOf(
                MethodAnalysisGap("payload escapes", "Opaque.read()V", observedOwners = setOf("Payload")),
                MethodAnalysisGap("different payload", "Opaque.read()V", observedOwners = setOf("Unrelated")),
                MethodAnalysisGap("removed reader", "Opaque.removed()V", observedOwners = setOf("Payload")),
            )))
        val comparison = SnapshotComparison(base, base)
        assertEquals(listOf("payload escapes"), comparison.analysisGaps(obligation(hidden.identity)))
        assertTrue(comparison.analysisGaps(obligation(root.identity)).isEmpty())
    }

    @Test
    fun `whole program preserves allocated callbacks static field initialization and inherited new entries`() {
        val root = method("Example.run()V", calls = setOf("Callback.<init>()V"), references = listOf("State.value:I"))
        val constructor = method("Callback.<init>()V")
        val callback = method("Callback.onEvent()V", "old callback")
        val initializer = method("State.<clinit>()V", "old initializer")
        val base = snapshot(root, constructor, callback, initializer)
        val override = method("Example.onExternalEvent()V", "new override")
        val head = snapshot(root, constructor, callback.copy(referenceCanonical = "new callback"),
            initializer.copy(referenceCanonical = "new initializer"), override).copy(overrides = setOf(override.identity))
        val comparison = SnapshotComparison(base, head)
        val expected = listOf(callback.identity, override.identity, initializer.identity).sorted()
        assertEquals(expected, comparison.wholeProgramDifferences())
        assertEquals(legacyClassUnion(base, head, comparison), comparison.wholeProgramDifferences())
    }

    @Test
    fun `whole program compares method contracts while ignoring explicitly unobserved field churn`() {
        val root = method("Example.run()V")
        val field = FieldSnapshot("Example.unused:I", "private int", "0", renameSafe = true)
        val base = snapshot(root).copy(declarations = mapOf("Example" to "class", root.identity to "old method contract"),
            classShapes = mapOf("Example" to "public class"), fields = mapOf(field.identity to field),
            ignoredFields = setOf(field.identity))
        val head = base.copy(declarations = base.declarations + (root.identity to "new method contract"), fields = emptyMap())
        val comparison = SnapshotComparison(base, head)
        assertEquals(listOf(root.identity), comparison.wholeProgramDifferences())
        assertEquals(legacyClassUnion(base, head, comparison), comparison.wholeProgramDifferences())
    }

    @Test
    fun `whole program does not invent a path by stitching edges from different revisions`() {
        val entry = method("Example.run()V", calls = setOf("Helper.work()V"))
        val helper = method("Helper.work()V", isPrivate = true)
        val dead = method("Dead.work()V", "old", isPrivate = true)
        val base = snapshot(entry, helper, dead)
        val head = snapshot(entry.copy(referenceCanonical = "entry no longer calls helper", calls = emptySet()),
            helper.copy(referenceCanonical = "helper now calls dead", calls = setOf(dead.identity)),
            dead.copy(referenceCanonical = "new"))
        assertEquals(listOf(entry.identity, helper.identity), SnapshotComparison(base, head).wholeProgramDifferences())
    }

    private fun legacyClassUnion(base: ProgramSnapshot, head: ProgramSnapshot, comparison: SnapshotComparison): List<String> =
        (base.declarations.keys + head.declarations.keys).filterNot { '(' in it }.flatMap { owner ->
            comparison.differences(ProofObligation(owner, SymbolKind.CLASS, owner, emptyList()))
        }.distinct().sorted()

    private fun obligation(identity: String) = ProofObligation(identity, SymbolKind.METHOD,
        identity.substringBefore('(').substringBeforeLast('.'), emptyList())

    private fun method(
        identity: String, body: String = "body", calls: Set<String> = emptySet(),
        references: List<String> = emptyList(), isPrivate: Boolean = false,
    ) = MethodSnapshot(identity, body, calls, setOf(if (isPrivate) MethodModifier.PRIVATE else MethodModifier.PUBLIC),
        emptySet(), references = references)

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" },
        emptyList(), emptyList(), entryClasses = setOf("Example"))
}
