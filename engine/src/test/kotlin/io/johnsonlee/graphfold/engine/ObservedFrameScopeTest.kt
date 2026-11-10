package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservedFrameScopeTest {
    @Test fun `renamed frame itself and transitive recursive callees can capture its runtime name`() {
        val program = snapshot(method("Renamed.run()V", "Helper.one()V"),
            method("Helper.one()V", "Helper.two()V"), method("Helper.two()V", "Helper.one()V"))
        val guarded = program.copy(methodDiagnostics = mapOf(
            "Renamed.run()V" to listOf(frameGap("self")), "Helper.two()V" to listOf(frameGap("callee"))))
        val comparison = SnapshotComparison(guarded, guarded)
        assertEquals(listOf("callee", "self"), comparison.analysisGaps())
        assertEquals(listOf("callee", "self"), comparison.analysisGaps(obligation("Renamed.run()V")))
    }

    @Test fun `reachable callers siblings and unrelated same-owner methods do not imply a renamed frame`() {
        val program = snapshot(method("Entry.run()V", "Renamed.run()V", "Helper.sibling()V"),
            method("Renamed.run()V", "Helper.called()V"), method("Helper.called()V"),
            method("Helper.sibling()V"), method("Other.observe()V"))
        val guarded = program.copy(methodDiagnostics = listOf("Entry.run()V", "Helper.sibling()V", "Other.observe()V")
            .associateWith { listOf(frameGap(it).copy(globalObservation = true)) })
        val comparison = SnapshotComparison(guarded, guarded)
        assertTrue(comparison.analysisGaps().isEmpty())
        assertTrue(comparison.analysisGaps(obligation("Renamed.run()V")).isEmpty())
    }

    @Test fun `dead renamed frames and dead observers never become active through scope expansion`() {
        val renamed = method("Renamed.run()V", "Helper.observe()V").copy(modifiers = setOf(MethodModifier.PRIVATE))
        val deadObserver = method("Helper.dead()V").copy(modifiers = setOf(MethodModifier.PRIVATE))
        val program = snapshot(method("Entry.run()V"), renamed, method("Helper.observe()V"), deadObserver)
            .copy(methodDiagnostics = mapOf("Helper.observe()V" to listOf(frameGap("unrelated")),
                deadObserver.identity to listOf(frameGap("dead"))))
        assertTrue(SnapshotComparison(program, program).analysisGaps().isEmpty())
    }

    @Test fun `base and head scopes must not manufacture a call path by combining revisions`() {
        val base = snapshot(method("Renamed.run()V", "Bridge.run()V"), method("Bridge.run()V"), method("Reader.read()V"))
        val head = snapshot(method("Renamed.run()V"), method("Bridge.run()V", "Reader.read()V"), method("Reader.read()V"))
        val diagnostics = mapOf("Reader.read()V" to listOf(frameGap("reader")))
        assertTrue(SnapshotComparison(base.copy(methodDiagnostics = diagnostics), head.copy(methodDiagnostics = diagnostics))
            .analysisGaps().isEmpty())
        val captured = base.copy(methodDiagnostics = mapOf("Bridge.run()V" to listOf(frameGap("base capture"))))
        assertEquals(listOf("base capture"), SnapshotComparison(captured, head).analysisGaps())
        assertEquals(listOf("base capture"), SnapshotComparison(head, captured).analysisGaps())
    }

    @Test fun `implicit own superclass field and constructor initialization retains capture evidence`() {
        val root = method("Renamed.run()V", "Constructed.<init>()V", "StaticTarget.call()V")
            .copy(references = listOf("Fields.VALUE:I", "Ignored.method()V"))
        val initializers = listOf("Renamed", "Parent", "Fields", "Constructed", "StaticTarget")
            .map { method("$it.<clinit>()V") }
        val program = snapshot(root, method("Constructed.<init>()V"), method("StaticTarget.call()V"), *initializers.toTypedArray())
            .copy(initializationDependencies = mapOf("Renamed" to setOf("Parent")),
                methodDiagnostics = initializers.associate { it.identity to listOf(frameGap(it.identity)) })
        assertEquals(initializers.map { it.identity }.sorted(), SnapshotComparison(program, program).analysisGaps())
    }

    @Test fun `constructed callback and graph dispatch edges preserve potential synchronous capture`() {
        val callback = method("Callback.run()V", "Reader.read()V")
        val deadPrivate = method("Callback.hidden()V").copy(modifiers = setOf(MethodModifier.PRIVATE))
        val program = snapshot(method("Renamed.run()V", "Callback.<init>()V", "Dispatched.run()V"),
            method("Callback.<init>()V"), callback, deadPrivate, method("Reader.read()V"), method("Dispatched.run()V"))
            .copy(methodDiagnostics = mapOf("Reader.read()V" to listOf(frameGap("callback")),
                "Dispatched.run()V" to listOf(frameGap("dispatch")), deadPrivate.identity to listOf(frameGap("private"))))
        assertEquals(listOf("callback", "dispatch"), SnapshotComparison(program, program).analysisGaps())
    }

    @Test fun `cross-thread or escaped trace observations remain active without renamed frames below them`() {
        val observer = "Other.inspect()V"
        val program = snapshot(method("Renamed.run()V"), method(observer))
            .copy(methodDiagnostics = mapOf(observer to listOf(frameGap("cross thread").copy(requiresObservedFrame = false))))
        val comparison = SnapshotComparison(program, program)
        assertEquals(listOf("cross thread"), comparison.analysisGaps())
        assertEquals(listOf("cross thread"), comparison.analysisGaps(obligation("Renamed.run()V")))
    }

    @Test fun `frame scope still requires surviving direct and dynamic observation sites`() {
        val identity = "Renamed.run()V"
        val program = snapshot(method(identity, "Throwable.capture()V").copy(dynamicCalls = setOf("get()LSupplier;")))
            .copy(methodDiagnostics = mapOf(identity to listOf(
                frameGap("direct").copy(callee = "Throwable.capture()V"),
                frameGap("removed direct").copy(callee = "Throwable.removed()V"),
                frameGap("dynamic").copy(dynamicCall = "get()LSupplier;"),
                frameGap("removed dynamic").copy(dynamicCall = "removed()LSupplier;"))))
        assertEquals(listOf("direct", "dynamic"), SnapshotComparison(program, program).analysisGaps())
        val noOwners = program.copy(methodDiagnostics = mapOf(identity to listOf(frameGap("empty").copy(observedOwners = emptySet()))))
        assertTrue(SnapshotComparison(noOwners, noOwners).analysisGaps().isEmpty())
    }

    @Test fun `absent owner and external constructed types do not invent observed frames`() {
        val program = snapshot(method("Renamed.run()V", "External.<init>()V"))
        val scope = ObservedFrameScope(program, program.methods.keys)
        assertTrue(scope.contains("Renamed.run()V", setOf("Renamed")))
        assertEquals(false, scope.contains("External.<init>()V", setOf("Renamed")))
        assertEquals(false, scope.contains("Renamed.run()V", setOf("Missing")))
    }

    private fun frameGap(message: String) = MethodAnalysisGap(message, observedOwners = setOf("Renamed"), requiresObservedFrame = true)
    private fun method(identity: String, vararg calls: String) =
        MethodSnapshot(identity, "body", calls.toSet(), setOf(MethodModifier.PUBLIC), emptySet())
    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "class" }, emptyList(), emptyList())
    private fun obligation(identity: String) = ProofObligation(identity, SymbolKind.METHOD,
        identity.substringBefore('(').substringBeforeLast('.'), emptyList())
}
