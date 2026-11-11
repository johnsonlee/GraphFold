package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.FieldSnapshot
import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchCoverageTest {
    private val entry = method("Example.run()V", "entry body")
    private val hidden = method("Example.hidden()V", "hidden body")
    private val base = ProgramSnapshot(
        mapOf(entry.identity to entry, hidden.identity to hidden),
        mapOf("Example" to "class", entry.identity to "public method", hidden.identity to "private method"),
        emptyList(), emptyList(), entryClasses = setOf("Example"),
    )

    @Test fun `an unchanged application covers any possibly omitted application dispatch target`() {
        assertTrue(DispatchCoverage(base, base).covers(emptySet()))
        assertTrue(DispatchCoverage(base, base).covers(setOf(entry.identity)))
    }

    @Test fun `a changed body is covered only when the caller actually compares that method`() {
        val changed = hidden.copy(canonical = "changed body", referenceCanonical = "changed body")
        val head = base.copy(methods = base.methods + (hidden.identity to changed))
        val coverage = DispatchCoverage(base, head)
        assertFalse(coverage.covers(setOf(entry.identity)))
        assertTrue(coverage.covers(setOf(entry.identity, hidden.identity)))
        assertFalse(DispatchCoverage(head, base).covers(setOf(entry.identity)))
    }

    @Test fun `method declaration changes require the method to be inside the actual comparison closure`() {
        val head = base.copy(declarations = base.declarations + (hidden.identity to "changed method annotation"))
        val coverage = DispatchCoverage(base, head)
        assertFalse(coverage.covers(setOf(entry.identity)))
        assertTrue(coverage.covers(setOf(entry.identity, hidden.identity)))
        assertFalse(DispatchCoverage(head, base).covers(setOf(entry.identity)))
        assertTrue(DispatchCoverage(head, base).covers(setOf(hidden.identity)))
    }

    @Test fun `a new method and its declaration are covered when the comparison includes the new method`() {
        val added = method("Example.added()V", "new synthetic lambda body")
        val head = base.copy(methods = base.methods + (added.identity to added),
            declarations = base.declarations + (added.identity to "private static synthetic method"))
        assertFalse(DispatchCoverage(base, head).covers(base.methods.keys))
        assertTrue(DispatchCoverage(base, head).covers(head.methods.keys))
        assertFalse(DispatchCoverage(head, base).covers(base.methods.keys))
        assertTrue(DispatchCoverage(head, base).covers(head.methods.keys))
    }

    @Test fun `a changed declaration without a captured method cannot borrow a caller comparison set`() {
        val missing = "Example.missing()V"
        val head = base.copy(declarations = base.declarations + (missing to "uncaptured method"))
        assertFalse(DispatchCoverage(base, head).covers(base.methods.keys + missing))
    }

    @Test fun `reference normalization cannot hide raw body changes outside the comparison closure`() {
        val old = hidden.copy(canonical = "call Example.before", referenceCanonical = "call @member0")
        val new = old.copy(canonical = "call Example.after")
        val before = base.copy(methods = base.methods + (hidden.identity to old))
        val after = base.copy(methods = base.methods + (hidden.identity to new))
        assertFalse(DispatchCoverage(before, after).covers(setOf(entry.identity)))
    }

    @Test fun `new and omitted method captures outside the closure cannot be treated as unchanged`() {
        val added = method("Example.added()V", "added body")
        val extended = base.copy(methods = base.methods + (added.identity to added))
        val omitted = base.copy(methods = base.methods - hidden.identity)
        assertFalse(DispatchCoverage(base, extended).covers(setOf(entry.identity)))
        assertFalse(DispatchCoverage(extended, base).covers(setOf(entry.identity)))
        assertFalse(DispatchCoverage(base, omitted).covers(setOf(entry.identity)))
        assertFalse(DispatchCoverage(omitted, base).covers(setOf(entry.identity)))
    }

    @Test fun `global inventory failures prevent a coverage claim even for equal captured methods`() {
        val incomplete = base.copy(diagnostics = listOf("class resolution failed"))
        assertFalse(DispatchCoverage(base, incomplete).covers(base.methods.keys))
        assertFalse(DispatchCoverage(incomplete, base).covers(base.methods.keys))
    }

    @Test fun `class field and normalization metadata must remain identical`() {
        val field = FieldSnapshot("Example.value:I", "static int", "1", renameSafe = false)
        val variants = listOf(
            base.copy(declarations = base.declarations + ("Example" to "changed class")),
            base.copy(fields = mapOf(field.identity to field)),
            base.copy(classShapes = mapOf("Example" to "changed shape")),
            base.copy(ignoredFields = setOf(field.identity)),
            base.copy(enclosingClasses = mapOf("Example" to "Outer")),
            base.copy(originalOwners = mapOf("Example" to "Original")),
        )
        variants.forEach { head -> assertFalse(DispatchCoverage(base, head).covers(base.methods.keys), head.toString()) }
    }

    @Test fun `entry override and initialization scope changes invalidate dispatch coverage`() {
        val variants = listOf(
            base.copy(entryClasses = null),
            base.copy(entryClasses = emptySet()),
            base.copy(externalEntries = setOf(hidden.identity)),
            base.copy(initializationDependencies = mapOf("Example" to setOf("Initializer"))),
            base.copy(overrides = setOf(hidden.identity)),
            base.copy(initializationTriggerMethods = setOf("Marker.value()V")),
        )
        variants.forEach { head -> assertFalse(DispatchCoverage(base, head).covers(base.methods.keys), head.toString()) }
    }

    @Test fun `equal nonempty declaration and entry inventories remain eligible`() {
        val field = FieldSnapshot("Example.value:I", "static int", "1", renameSafe = false)
        val snapshot = base.copy(fields = mapOf(field.identity to field), classShapes = mapOf("Example" to "shape"),
            ignoredFields = setOf(field.identity), enclosingClasses = mapOf("Example" to "Outer"),
            originalOwners = mapOf("Example" to "Original"), externalEntries = setOf(hidden.identity),
            initializationDependencies = mapOf("Example" to setOf("Initializer")), overrides = setOf(hidden.identity),
            initializationTriggerMethods = setOf("Initializer.value()V"))
        assertTrue(DispatchCoverage(snapshot, snapshot).covers(emptySet()))
    }

    private fun method(identity: String, body: String) = MethodSnapshot(
        identity, body, emptySet(), setOf(MethodModifier.PUBLIC), emptySet(),
    )
}
