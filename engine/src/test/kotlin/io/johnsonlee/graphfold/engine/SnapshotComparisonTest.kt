package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.MethodSnapshot
import io.johnsonlee.graphfold.engine.graphite.ProgramSnapshot
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotComparisonTest {
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
        val base = snapshot(root, helper, method("Generated.value()I", "old"))
        val head = snapshot(root, helper, method("Generated.value()I", "new"))
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
    fun `new override on an unused new class is not an entry point`() {
        val base = snapshot(method("Example.run()I")).copy(entryClasses = setOf("Example"))
        val added = method("Unused.isEmpty()Z", "return true")
        val head = base.copy(methods = base.methods + (added.identity to added),
            declarations = base.declarations + ("Unused" to "class"), overrides = setOf(added.identity))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.isUnreachable(obligation(added.identity)))
        assertTrue(comparison.differences(obligation(added.identity)).isEmpty())
        assertTrue(comparison.isUnreachable(ProofObligation("Unused", SymbolKind.CLASS, "Unused", emptyList())))
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
    fun `allocating an object does not make its uncalled private helper observable`() {
        val root = method("Example.run()I", calls = setOf("Generated.<init>()V"))
        val constructor = method("Generated.<init>()V")
        val helper = method("Generated.unused()I", "return 1", isPrivate = true)
        val base = snapshot(root, constructor, helper).copy(entryClasses = setOf("Example"))
        val head = snapshot(root, constructor, helper.copy(canonical = "return 2", referenceCanonical = "return 2"))
            .copy(entryClasses = setOf("Example"))
        assertTrue(differences(base, head).isEmpty())
        assertTrue(SnapshotComparison(base, head).isUnreachable(obligation(helper.identity)))
    }

    private fun differences(base: ProgramSnapshot, head: ProgramSnapshot): List<String> =
        SnapshotComparison(base, head).differences(obligation("Example.run()I"))

    private fun obligation(identity: String) = ProofObligation(
        identity, SymbolKind.METHOD, identity.substringBefore('(').substringBeforeLast('.'), emptyList(),
    )

    private fun method(identity: String, canonical: String = "body", calls: Set<String> = emptySet(), isPrivate: Boolean = false) =
        MethodSnapshot(identity, canonical, calls, setOf(if (isPrivate) "PRIVATE" else "PUBLIC"), emptySet())

    private fun snapshot(vararg methods: MethodSnapshot) = ProgramSnapshot(
        methods.associateBy { it.identity },
        methods.associate { it.identity.substringBefore('(').substringBeforeLast('.') to "declaration" },
        emptyList(), emptyList(),
    )
}
