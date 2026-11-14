package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.snapshot.FieldSnapshot

import sootup.core.model.MethodModifier
import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotComparisonTest {
    @Test fun `inline property obligations retain uncalled accessor declarations`() {
        val root = method("Example.run()I")
        val getter = method("Example.getBump()I", "return 1", isPrivate = true)
        val setter = method("Example.setBump(I)V", "state = input", isPrivate = true)
        val base = snapshot(root, getter, setter)
        val head = snapshot(root, getter.copy(canonical = "return 2", referenceCanonical = "return 2"),
            setter.copy(canonical = "state = input + 1", referenceCanonical = "state = input + 1"))
        val obligation = ProofObligation("Example", SymbolKind.CLASS, "Example", emptyList(), inlined = true)
        assertEquals(listOf(getter.identity, setter.identity), SnapshotComparison(base, head).differences(obligation))
    }

    @Test
    fun `resource entry effects include existing inherited APIs but not new unused public types`() {
        val oldRead = ResourceRead("messages", "old")
        val newRead = ResourceRead("messages", "new")
        val base = snapshot().copy(entryClasses = setOf("Existing"),
            resourceEntryReads = mapOf("Existing" to listOf(oldRead)), resourceDependenciesAnalyzed = true)
        val head = base.copy(entryClasses = setOf("Existing", "Added"),
            resourceEntryReads = mapOf("Existing" to listOf(oldRead), "Added" to listOf(newRead)))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.reachableSnapshots().isEmpty())
        assertEquals(listOf(oldRead, oldRead), comparison.resourceEntryReads)
        assertTrue(comparison.resourceDependenciesAnalyzed)
        assertTrue(!SnapshotComparison(base, head.copy(resourceDependenciesAnalyzed = false)).resourceDependenciesAnalyzed)
        assertTrue(!SnapshotComparison(base.copy(resourceDependenciesAnalyzed = false), head).resourceDependenciesAnalyzed)
        val external = head.copy(externalEntries = setOf("Added.callback()V"))
        assertTrue(newRead in SnapshotComparison(base, external).resourceEntryReads)
        val root = method("Existing.run()V", calls = setOf("Added.<init>()V"))
        val activeBase = base.copy(methods = mapOf(root.identity to root))
        val constructor = method("Added.<init>()V", isPrivate = true)
        val activeHead = head.copy(methods = mapOf(root.identity to root, constructor.identity to constructor))
        assertTrue(newRead in SnapshotComparison(activeBase, activeHead).resourceEntryReads)
    }

    @Test
    fun `source annotation change reaches a generated callee even when annotated IR is unchanged`() {
        val root = method("Example.run()I", calls = setOf("Generated.value()I"))
        val base = snapshot(root, method("Generated.value()I", "return 1"))
        val head = snapshot(root, method("Generated.value()I", "return 2"))
        assertEquals(listOf("Generated.value()I"), differences(base, head))
    }

    @Test
    fun `transitive dependency traversal terminates on recursive generated calls`() {
        val root = method("Example.run()I", calls = setOf("Generated.first()I"))
        val first = method("Generated.first()I", calls = setOf("Generated.second()I"))
        val base = snapshot(root, first, method("Generated.second()I", "old", setOf(first.identity)))
        val head = snapshot(root, first, method("Generated.second()I", "new", setOf(first.identity)))
        assertEquals(listOf("Generated.second()I"), differences(base, head))
    }

    @Test
    fun `base-only edges retain removed generated dependency evidence`() {
        val base = snapshot(method("Example.run()I", calls = setOf("Generated.value()I")), method("Generated.value()I", "old"))
        val head = snapshot(method("Example.run()I"))
        assertTrue("Generated.value()I" in differences(base, head))
    }

    @Test
    fun `unrelated generated classes do not broaden a method obligation globally`() {
        val root = method("Example.run()I")
        assertTrue(differences(snapshot(root, method("Other.run()I", "old")),
            snapshot(root, method("Other.run()I", "new"))).isEmpty())
    }

    @Test
    fun `folded-away private helper remains excluded including its generated dependencies`() {
        val root = method("Example.run()I")
        val helper = method("Example.helper()I", calls = setOf("Generated.value()I"), isPrivate = true)
        val base = snapshot(root, helper, method("Generated.value()I", "old")).copy(controlRemovedMethods = setOf(helper.identity))
        val head = snapshot(root, helper, method("Generated.value()I", "new")).copy(controlRemovedMethods = setOf(helper.identity))
        assertTrue(SnapshotComparison(base, head).differences(obligation(helper.identity)).isEmpty())
        assertTrue(differences(base, head).isEmpty())
    }

    @Test
    fun `private generated helper still participates when a surviving call reaches it`() {
        val root = method("Example.run()I", calls = setOf("Generated.helper()I"))
        val base = snapshot(root, method("Generated.helper()I", "old", isPrivate = true))
        val head = snapshot(root, method("Generated.helper()I", "new", isPrivate = true))
        assertEquals(listOf("Generated.helper()I"), differences(base, head))
    }

    @Test
    fun `anonymous returned object's callback is part of enclosing method proof`() {
        val root = method("Example.run()I")
        val callback = "Example\$1.run()V"
        val base = snapshot(root, method(callback, "old")).copy(enclosingClasses = mapOf("Example\$1" to "Example"))
        val head = snapshot(root, method(callback, "new")).copy(enclosingClasses = mapOf("Example\$1" to "Example"))
        assertEquals(listOf(callback), differences(base, head))
    }

    @Test
    fun `dollar sign prefixes do not imply compiler nesting`() {
        val root = method("Example.run()I")
        val other = "Example\$Unrelated.run()V"
        assertTrue(differences(snapshot(root, method(other, "old")), snapshot(root, method(other, "new"))).isEmpty())
    }

    @Test
    fun `actual enclosing relations follow renamed nested owners across both revisions`() {
        val root = method("Example.run()I")
        val nested = "EmittedOtherName.run()V"
        val base = snapshot(root, method(nested, "old"))
            .copy(enclosingClasses = mapOf("EmittedOtherName" to "Middle", "Middle" to "Example"))
        val head = snapshot(root, method(nested, "new"))
            .copy(enclosingClasses = mapOf("EmittedOtherName" to "SecondParent", "SecondParent" to "Example"))
        assertEquals(listOf(nested), differences(base, head))
    }

    @Test
    fun `corrupt cyclic nesting cannot create a relationship to an unrelated owner`() {
        val root = method("Example.run()I")
        val other = method("Other.run()V", "old")
        val base = snapshot(root, other).copy(enclosingClasses = mapOf("Other" to "Loop", "Loop" to "Other"))
        val head = base.copy(methods = base.methods + (other.identity to other.copy(canonical = "new", referenceCanonical = "new")))
        assertTrue(differences(base, head).isEmpty())
    }

    @Test
    fun `allocated external generated object includes escaped callable methods`() {
        val root = method("Example.run()I", calls = setOf("Generated.<init>()V"))
        val constructor = method("Generated.<init>()V")
        val base = snapshot(root, constructor, method("Generated.run()V", "old"))
        val head = snapshot(root, constructor, method("Generated.run()V", "new"))
        assertEquals(listOf("Generated.run()V"), differences(base, head))
    }

    @Test
    fun `reachable generated class declarations and initializers are compared`() {
        val root = method("Example.run()I", calls = setOf("Generated.value()I"))
        val value = method("Generated.value()I")
        val base = snapshot(root, value, method("Generated.<clinit>()V", "old"))
        val head = snapshot(root, value, method("Generated.<clinit>()V", "new"))
            .let { it.copy(declarations = it.declarations + ("Generated" to "changed generated fields")) }
        assertEquals(listOf("Generated", "Generated.<clinit>()V"), differences(base, head))
    }

    @Test
    fun `field obligations preserve global constant inlining comparison`() {
        val root = method("Example.run()I")
        val base = snapshot(root, method("Other.use()I", "constant 1"))
        val head = snapshot(root, method("Other.use()I", "constant 2"))
        val obligation = ProofObligation("Example.LIMIT:I", SymbolKind.FIELD, "Example", emptyList())
        assertEquals(listOf("Other.use()I"), SnapshotComparison(base, head).differences(obligation))
    }

    @Test
    fun `missing declaration is explicitly unrepresented`() {
        val snapshot = snapshot(method("Example.run()I"))
        assertEquals(listOf("Unrepresented declaration Missing.run()I"),
            SnapshotComparison(snapshot, snapshot).differences(obligation("Missing.run()I")))
    }

    @Test
    fun `an unrepresented method in a represented owner is not accepted as unreachable proof`() {
        val program = snapshot(method("Example.run()I"))
        assertEquals(listOf("Unrepresented declaration Example.missing()I"),
            SnapshotComparison(program, program).differences(obligation("Example.missing()I")))
    }

    @Test
    fun `method declaration changes remain observable when IR and references are unchanged`() {
        val root = method("Example.run()I")
        val before = snapshot(root).copy(declarations = mapOf("Example" to "class", root.identity to "public int"))
        val after = before.copy(declarations = before.declarations + (root.identity to "public synchronized int"))
        assertEquals(listOf(root.identity), differences(before, after))
    }

    @Test
    fun `reference changes remain observable even with identical placeholder IR`() {
        val root = method("Example.run()I").copy(references = listOf("External.first:I"))
        val before = snapshot(root)
        val after = snapshot(root.copy(references = listOf("External.second:I")))
        assertEquals(listOf(root.identity), differences(before, after))
    }

    @Test
    fun `new override remains a production change without an inferred invocation`() {
        val base = snapshot(method("Example.run()I")).copy(entryClasses = setOf("Example"))
        val added = method("Unused.isEmpty()Z", "return true")
        val head = base.copy(methods = base.methods + (added.identity to added),
            declarations = base.declarations + ("Unused" to "class"), overrides = setOf(added.identity))
        val comparison = SnapshotComparison(base, head)
        assertTrue(!comparison.isUnreachable(obligation(added.identity)))
        assertEquals(listOf("Unused", added.identity), comparison.differences(obligation(added.identity)))
        assertTrue(!comparison.isUnreachable(ProofObligation("Unused", SymbolKind.CLASS, "Unused", emptyList())))
    }

    @Test
    fun `initialization edge changes affect only the initializing owner and changed dependency`() {
        val base = snapshot(method("Example.run()I"), method("Other.run()I"), method("Marker.<clinit>()V"))
            .copy(entryClasses = setOf("Example", "Other"), initializationDependencies = mapOf("Example" to emptySet()))
        val head = base.copy(initializationDependencies = mapOf("Example" to setOf("Marker")))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation("Example.run()I")).any { it.contains("initialization dependencies") })
        assertTrue(comparison.differences(ProofObligation("Marker", SymbolKind.CLASS, "Marker", emptyList()))
            .any { it.contains("initialization dependencies") })
        assertTrue(comparison.differences(obligation("Other.run()I")).isEmpty())
    }

    @Test
    fun `new inherited override on an existing entry class remains externally callable`() {
        val root = method("Example.run()I")
        val added = method("Example.isEmpty()Z", "return true")
        val base = snapshot(root).copy(entryClasses = setOf("Example"))
        val head = snapshot(root, added).copy(entryClasses = setOf("Example"), overrides = setOf(added.identity))
        assertEquals(listOf(added.identity), SnapshotComparison(base, head).differences(obligation(added.identity)))
    }

    @Test
    fun `caller closure stays bounded while an uncalled private source edit remains uncontrolled`() {
        val root = method("Example.run()I", calls = setOf("Generated.<init>()V"))
        val constructor = method("Generated.<init>()V")
        val helper = method("Generated.unused()I", "return 1", isPrivate = true)
        val base = snapshot(root, constructor, helper).copy(entryClasses = setOf("Example"))
        val head = snapshot(root, constructor, helper.copy(canonical = "return 2", referenceCanonical = "return 2"))
            .copy(entryClasses = setOf("Example"))
        assertTrue(differences(base, head).isEmpty())
        assertEquals(listOf(helper.identity), SnapshotComparison(base, head).differences(obligation(helper.identity)))
    }

    @Test
    fun `owner indexes retain reachable static fields and callbacks without pulling unrelated owners`() {
        val root = method("Example.run()I", calls = setOf("Callback.<init>()V"))
            .copy(references = listOf("State.value:I"))
        val constructor = method("Callback.<init>()V")
        val callback = method("Callback.run()V", "old callback")
        val initializer = method("State.<clinit>()V", "old initializer")
        val unrelated = method("Unrelated.run()V", "old unrelated")
        val base = snapshot(root, constructor, callback, initializer, unrelated).copy(entryClasses = setOf("Example"))
        val head = snapshot(root, constructor, callback.copy(referenceCanonical = "new callback"),
            initializer.copy(referenceCanonical = "new initializer"), unrelated.copy(referenceCanonical = "new unrelated"))
            .copy(entryClasses = setOf("Example"))
        val comparison = SnapshotComparison(base, head)
        val field = ProofObligation("State.value:I", SymbolKind.FIELD, "State", emptyList())
        assertEquals(listOf(callback.identity, initializer.identity), comparison.differences(field))
        assertEquals(listOf(unrelated.identity), comparison.differences(obligation(unrelated.identity)))
        assertEquals(listOf(callback.identity, initializer.identity), comparison.differences(field))
    }

    @Test
    fun `inline obligation reaches copied code without invocation edges across owners`() {
        val helper = method("PrivateFacade.bump(I)I", "old helper", isPrivate = true)
        val oldCaller = method("Example.run()I", "copied old body")
        val newCaller = oldCaller.copy(referenceCanonical = "copied new body")
        val base = snapshot(oldCaller, helper).copy(entryClasses = setOf("Example"))
        val head = snapshot(newCaller, helper.copy(referenceCanonical = "new helper")).copy(entryClasses = setOf("Example"))
        val comparison = SnapshotComparison(base, head)
        assertEquals(listOf(helper.identity), comparison.differences(obligation(helper.identity)))
        val inline = obligation(helper.identity).copy(inlined = true)
        assertEquals(listOf(oldCaller.identity, helper.identity).sorted(), comparison.differences(inline))
        assertTrue(!comparison.isUnreachable(inline))
    }

    @Test
    fun `uncalled inline source body still requires a control proof`() {
        val helper = method("PrivateFacade.bump(I)I", "old helper", isPrivate = true)
        val root = method("Example.run()I")
        val base = snapshot(root, helper).copy(entryClasses = setOf("Example"))
        val head = snapshot(root, helper.copy(referenceCanonical = "new helper")).copy(entryClasses = setOf("Example"))
        val comparison = SnapshotComparison(base, head)
        val inline = obligation(helper.identity).copy(inlined = true)
        assertEquals(listOf(helper.identity), comparison.differences(inline))
        assertTrue(!comparison.isUnreachable(inline))
    }

    @Test fun `literal static comparison does not require external runtime observation semantics`() {
        val root = method("Example.run()I", calls = setOf("External.consume()V"))
        val gap = MethodAnalysisGap("Unknown external observer", "External.consume()V",
            globalObservation = true, externalObservationOnly = true)
        val base = snapshot(root).copy(methodDiagnostics = mapOf(root.identity to listOf(gap)))
        assertTrue(SnapshotComparison(base, base).analysisGaps().isEmpty())
        val changed = base.copy(methods = mapOf(root.identity to root.copy(referenceCanonical = "changed")))
        assertEquals(listOf(root.identity), SnapshotComparison(base, changed).wholeProgramDifferences())
        assertTrue(SnapshotComparison(base, changed).analysisGaps().isEmpty())
        assertEquals(listOf(gap.message), SnapshotComparison(base, base).resourceAnalysisGaps())
        val missingBody = base.copy(methodDiagnostics = mapOf(root.identity to listOf(gap.copy(externalObservationOnly = false))))
        assertEquals(listOf(gap.message), SnapshotComparison(missingBody, missingBody).analysisGaps())
        val omitted = base.copy(controlRemovedMethods = setOf("Hidden.callback()V"))
        assertEquals(listOf(gap.message), SnapshotComparison(base, omitted).analysisGaps())
        val metadata = base.copy(declarations = base.declarations + ("Example" to "changed metadata"))
        assertEquals(listOf(gap.message), SnapshotComparison(base, metadata).analysisGaps())
    }

    @Test fun `unknown dispatch cannot hide edits to uncalled application bodies in a literal comparison`() {
        val root = method("Example.run()I", calls = setOf("External.dispatch()V"))
        val callback = method("Example.callback()V", isPrivate = true)
        val gap = MethodAnalysisGap("Unresolved dispatch", "External.dispatch()V", dispatchOnly = true,
            globalObservation = true)
        val base = snapshot(root, callback).copy(methodDiagnostics = mapOf(root.identity to listOf(gap)),
            declarations = mapOf("Example" to "class", root.identity to "method", callback.identity to "private method"),
            entryClasses = setOf("Example"))
        assertTrue(SnapshotComparison(base, base).analysisGaps().isEmpty())
        val edited = callback.copy(canonical = "edited", referenceCanonical = "edited")
        val changed = base.copy(methods = base.methods + (callback.identity to edited))
        val comparison = SnapshotComparison(base, changed)
        assertTrue(comparison.analysisGaps().isEmpty())
        assertEquals(listOf(callback.identity), comparison.wholeProgramDifferences())
        val partialBase = base.copy(declarations = mapOf("Example" to "class"))
        val partialHead = changed.copy(declarations = partialBase.declarations)
        assertEquals(listOf(gap.message), SnapshotComparison(partialBase, partialHead).analysisGaps())
        val invalid = changed.copy(diagnostics = listOf("Missing inventory owner"))
        assertEquals(listOf(gap.message), SnapshotComparison(base, invalid).analysisGaps())
        val incomplete = changed.copy(methodDiagnostics = changed.methodDiagnostics +
            (callback.identity to listOf(MethodAnalysisGap("Missing callback body"))))
        assertTrue(gap.message in SnapshotComparison(base, incomplete).analysisGaps())
        val initializer = changed.copy(initializationDependencies = mapOf("Example" to setOf("NewInitializer")))
        assertTrue(gap.message in SnapshotComparison(base, initializer).analysisGaps())
        val observer = MethodAnalysisGap("External observer", globalObservation = true, externalObservationOnly = true)
        val observedBase = base.copy(methodDiagnostics = mapOf(root.identity to listOf(gap, observer)))
        val observedHead = changed.copy(methodDiagnostics = observedBase.methodDiagnostics)
        assertTrue(SnapshotComparison(observedBase, observedHead).analysisGaps().isEmpty())
        assertEquals(listOf(callback.identity), SnapshotComparison(observedBase, observedHead).wholeProgramDifferences())
        assertEquals(listOf(gap.message), SnapshotComparison(base, base).resourceAnalysisGaps())
        val missing = base.copy(methodDiagnostics = mapOf(root.identity to listOf(gap.copy(dispatchOnly = false))))
        assertEquals(listOf(gap.message), SnapshotComparison(missing, missing).analysisGaps())
    }

    @Test
    fun `one revision removal certificate cannot erase an existing unproved body in the other revision`() {
        val entry = method("Example.run()I")
        val hidden = method("Example.hidden()I", "old body", isPrivate = true)
        val base = snapshot(entry, hidden)
        val changed = hidden.copy(canonical = "new body", referenceCanonical = "new body")
        val head = snapshot(entry, changed).copy(controlRemovedMethods = setOf(hidden.identity))
        val comparison = SnapshotComparison(base, head)
        assertTrue(!comparison.isUnreachable(obligation(hidden.identity)))
        assertEquals(listOf(hidden.identity), comparison.differences(obligation(hidden.identity)))
        val removed = snapshot(entry)
        assertTrue(SnapshotComparison(base.copy(controlRemovedMethods = setOf(hidden.identity)), removed)
            .isUnreachable(obligation(hidden.identity)))
        assertTrue(!SnapshotComparison(base, removed).isUnreachable(obligation(hidden.identity)))
    }

    @Test
    fun `head-only fold removal cannot borrow literal inventory exemption for opaque observations`() {
        val entry = method("Example.run()I")
        val hidden = method("Example.hidden()I", isPrivate = true)
        val observer = MethodAnalysisGap("Opaque observer", globalObservation = true, externalObservationOnly = true)
        val base = snapshot(entry, hidden).copy(methodDiagnostics = mapOf(entry.identity to listOf(observer)))
        val head = base.copy(controlRemovedMethods = setOf(hidden.identity))
        assertEquals(listOf(observer.message), SnapshotComparison(base, head).analysisGaps())
    }

    @Test
    fun `base-only fold removal cannot borrow literal inventory exemption for opaque observations`() {
        val entry = method("Example.run()I")
        val hidden = method("Example.hidden()I", isPrivate = true)
        val observer = MethodAnalysisGap("Opaque observer", globalObservation = true, externalObservationOnly = true)
        val head = snapshot(entry, hidden).copy(methodDiagnostics = mapOf(entry.identity to listOf(observer)))
        val base = head.copy(controlRemovedMethods = setOf(hidden.identity))
        assertEquals(listOf(observer.message), SnapshotComparison(base, head).analysisGaps())
    }

    @Test
    fun `class-only metadata changes survive ordinary and inline class obligations without method inventory`() {
        val entry = method("Other.run()I")
        val base = snapshot(entry).copy(declarations = mapOf("Other" to "class", "Example" to "old annotation"))
        val head = base.copy(declarations = base.declarations + ("Example" to "new annotation"))
        val change = ProofObligation("Example", SymbolKind.CLASS, "Example", emptyList())
        val comparison = SnapshotComparison(base, head)
        assertEquals(listOf("Example"), comparison.differences(change))
        assertEquals(listOf("Example"), comparison.differences(change.copy(inlined = true)))
    }

    @Test
    fun `inline removal certificate does not discharge other surviving comparison roots`() {
        val root = method("Example.run()I", "old root")
        val helper = method("Inline.helper()I", "old inline body", isPrivate = true)
        val base = snapshot(root, helper).copy(controlRemovedMethods = setOf(helper.identity))
        val head = snapshot(root.copy(canonical = "new root", referenceCanonical = "new root"),
            helper.copy(canonical = "new inline body", referenceCanonical = "new inline body"))
            .copy(controlRemovedMethods = setOf(helper.identity))
        val change = obligation(helper.identity).copy(inlined = true)
        val comparison = SnapshotComparison(base, head)
        assertTrue(!comparison.isUnreachable(change))
        assertEquals(listOf(root.identity), comparison.differences(change))
    }

    @Test
    fun `indirect class metadata normalization does not erase explicit or whole program field changes`() {
        val root = method("Example.run()I")
        val field = io.johnsonlee.graphfold.engine.snapshot.FieldSnapshot("Example.hidden:I", "private int", "1", true)
        val base = snapshot(root).copy(classShapes = mapOf("Example" to "class"), fields = mapOf(field.identity to field),
            ignoredFields = setOf(field.identity))
        val head = base.copy(fields = mapOf(field.identity to field.copy(constant = "2")))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.differences(obligation(root.identity)).isEmpty())
        assertEquals(listOf("Example"), comparison.differences(ProofObligation("Example", SymbolKind.CLASS, "Example", emptyList())))
        assertEquals(listOf("Example"), comparison.wholeProgramDifferences())
    }

    private fun differences(base: ProgramSnapshot, head: ProgramSnapshot): List<String> =
        SnapshotComparison(base, head).differences(obligation("Example.run()I"))

    private fun obligation(identity: String) = ProofObligation(
        identity, SymbolKind.METHOD, identity.substringBefore('(').substringBeforeLast('.'), emptyList(),
    )

    private fun method(identity: String, canonical: String = "body", calls: Set<String> = emptySet(), isPrivate: Boolean = false) =
        MethodSnapshot(identity, canonical, calls, setOf(if (isPrivate) MethodModifier.PRIVATE else MethodModifier.PUBLIC), emptySet())

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(
        methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "declaration" },
        emptyList(), emptyList(),
    )
}
