package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.FieldSnapshot
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.StructuralClassShape
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import sootup.core.model.MethodModifier
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlledStructureComparisonTest {
    private val root = method("Example.run()V")
    private val constructor = method("Example\$Added.<init>()V")
    private val body = method("Example\$Added.apply()V")
    private val owner = "Example\$Added"
    private val obligation = ProofObligation(owner, SymbolKind.CLASS, owner, emptyList())

    @Test fun `certified owner removes only its own nesting and initialization records`() {
        val comparison = SnapshotComparison(base(), added())
        assertTrue(comparison.isUnreachable(obligation))
        assertTrue(comparison.differences(obligation).isEmpty())
        assertTrue(comparison.wholeProgramDifferences().isEmpty())
        assertTrue(SnapshotComparison(added(), base()).wholeProgramDifferences().isEmpty())
        val changedOuter = added().copy(structuralClassShapes = added().structuralClassShapes +
            ("Example" to StructuralClassShape("changed annotation", listOf(owner to "nest"))))
        assertTrue("Example" in SnapshotComparison(base(), changedOuter).wholeProgramDifferences())
    }

    @Test fun `unused method or initializer prevents structural removal`() {
        for (method in listOf(constructor, body, method("$owner.<clinit>()V"))) {
            val head = added().copy(methods = added().methods + (method.identity to method),
                controlRemovedMethods = added().controlRemovedMethods - method.identity)
            assertFalse(SnapshotComparison(base(), head).isUnreachable(obligation))
            assertTrue(SnapshotComparison(base(), head).differences(obligation).isNotEmpty())
        }
        val unused = added().copy(controlRemovedMethods = emptySet())
        assertTrue(SnapshotComparison(base(), unused).wholeProgramDifferences().isNotEmpty())
    }

    @Test fun `public discovery and surviving type observations veto structural proof`() {
        val snapshots = listOf(
            added().copy(entryClasses = setOf("Example", owner)),
            added().copy(externalEntries = setOf(body.identity)),
            added().copy(methods = added().methods + (root.identity to root.copy(canonical = "class LExample\$Added;"))),
            added().copy(methods = added().methods + (root.identity to root.copy(references = listOf("$owner.value:I")))),
            added().copy(fields = mapOf("Example.value:LExample\$Added;" to
                FieldSnapshot("Example.value:LExample\$Added;", "LExample\$Added;", "null", false))),
            added().copy(initializationDependencies = mapOf("Other" to setOf(owner))),
            added().copy(structuralClassShapes = added().structuralClassShapes +
                ("Other" to StructuralClassShape("superclass $owner", emptyList()))),
        )
        snapshots.forEach { head ->
            assertFalse(SnapshotComparison(base(), head).isUnreachable(obligation), head.toString())
            assertTrue(SnapshotComparison(base(), head).wholeProgramDifferences().isNotEmpty())
        }
    }

    @Test fun `existing revision requires its own removal certificate and field initialization proof`() {
        val before = added()
        val field = FieldSnapshot("$owner.factor:I", "private final int", "absent", true)
        val after = before.copy(fields = mapOf(field.identity to field))
        val fieldObligation = ProofObligation("$owner.factor", SymbolKind.FIELD, owner, emptyList())
        assertTrue(SnapshotComparison(before, after).isUnreachable(fieldObligation))
        assertTrue(SnapshotComparison(before, after).differences(fieldObligation).isEmpty())
        val uncertified = before.copy(controlRemovedMethods = before.controlRemovedMethods - constructor.identity)
        assertFalse(SnapshotComparison(uncertified, after).isUnreachable(fieldObligation))
        assertTrue(SnapshotComparison(uncertified, after).wholeProgramDifferences().isNotEmpty())
    }

    @Test fun `unrelated nested metadata survives while one certified class disappears`() {
        val before = base().copy(structuralClassShapes = mapOf("Example" to
            StructuralClassShape("outer", listOf("Other" to "old metadata"))))
        val after = added().copy(structuralClassShapes = added().structuralClassShapes +
            ("Example" to StructuralClassShape("outer", listOf(owner to "nest", "Other" to "new metadata"))))
        assertTrue("Example" in SnapshotComparison(before, after).wholeProgramDifferences())
    }

    @Test fun `surviving inline copies remain obligations when declaration owner is controlled`() {
        val before = added()
        val changed = root.copy(canonical = "copied body changed", referenceCanonical = "copied body changed")
        val after = before.copy(methods = before.methods + (root.identity to changed))
        val inline = obligation.copy(inlined = true)
        val comparison = SnapshotComparison(before, after)
        assertFalse(comparison.isUnreachable(inline))
        assertTrue(root.identity in comparison.differences(inline))
    }

    @Test fun `unrelated classes retain their literal metadata while a controlled class is removed`() {
        val before = base().copy(declarations = base().declarations + ("Other" to "old"),
            classShapes = base().classShapes + ("Other" to "literal old"),
            structuralClassShapes = base().structuralClassShapes + ("Other" to StructuralClassShape("same core", emptyList())))
        val after = added().copy(declarations = added().declarations + ("Other" to "new"),
            classShapes = added().classShapes + ("Other" to "literal new"),
            structuralClassShapes = added().structuralClassShapes + ("Other" to StructuralClassShape("same core", emptyList())))
        assertTrue("Other" in SnapshotComparison(before, after).wholeProgramDifferences())
    }

    @Test fun `partial owner inventory is not proof of absence`() {
        val complete = added()
        val variants = listOf(
            complete.copy(declarations = complete.declarations - owner),
            base().copy(fields = mapOf("$owner.field:I" to FieldSnapshot("$owner.field:I", "int", "absent", false))),
            base().copy(classShapes = base().classShapes + (owner to "shape")),
            base().copy(structuralClassShapes = base().structuralClassShapes + (owner to StructuralClassShape("shape", emptyList()))),
            base().copy(initializationDependencies = mapOf(owner to setOf("java.lang.Object"))),
        )
        variants.forEach { partial ->
            assertFalse(SnapshotComparison(partial, complete).isUnreachable(obligation), partial.toString())
        }
    }

    @Test fun `declared owner without methods cannot borrow the other revision certificate`() {
        val before = base().copy(declarations = base().declarations + (owner to "empty type"),
            classShapes = base().classShapes + (owner to "empty type"))
        assertFalse(ownerRemovedByControls(before, owner))
        assertFalse(SnapshotComparison(before, added()).isUnreachable(obligation))
    }

    @Test fun `JVM descriptor spelling of surviving class literal vetoes owner removal`() {
        val qualified = "sample.Hidden"
        val initializer = method("$qualified.<init>()V")
        val controlled = base().copy(methods = base().methods + (initializer.identity to initializer),
            declarations = base().declarations + (qualified to "class"),
            controlRemovedMethods = setOf(initializer.identity))
        assertTrue(ownerRemovedByControls(controlled, qualified))
        val observed = controlled.copy(methods = controlled.methods +
            (root.identity to root.copy(canonical = "class Lsample/Hidden;")))
        assertFalse(ownerRemovedByControls(observed, qualified))
    }

    private fun base() = ProgramSnapshot(mapOf(root.identity to root), mapOf("Example" to "outer"), emptyList(), listOf(1),
        classShapes = mapOf("Example" to "outer"), entryClasses = setOf("Example"),
        structuralClassShapes = mapOf("Example" to StructuralClassShape("outer", emptyList())))

    private fun added() = base().copy(
        methods = base().methods + mapOf(constructor.identity to constructor, body.identity to body),
        declarations = base().declarations + (owner to "added"),
        classShapes = base().classShapes + mapOf("Example" to "outer with nest", owner to "added"),
        controlRemovedMethods = setOf(constructor.identity, body.identity),
        initializationDependencies = mapOf(owner to setOf("java.lang.Object")),
        structuralClassShapes = mapOf("Example" to StructuralClassShape("outer", listOf(owner to "nest")),
            owner to StructuralClassShape("added", emptyList())),
    )

    private fun method(identity: String) = MethodSnapshot(identity, "return", emptySet(), setOf(MethodModifier.PUBLIC), emptySet())
}
