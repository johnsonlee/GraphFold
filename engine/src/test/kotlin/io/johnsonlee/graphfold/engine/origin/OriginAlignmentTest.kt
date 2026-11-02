package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OriginAlignmentTest {
    @Test fun `proven source pair preserves original base owner as comparison label`() {
        val text = "class Example { Object item = new Object() { String literal = \"Example$1\"; }; }"
        val before = java(text, "Example$1")
        val stable = alignOrigins(before, before)
        assertTrue(stable.diagnostics.isEmpty())
        assertEquals(mapOf("Example$1" to "Example$1"), stable.baseOwners)
        assertEquals(stable.baseOwners, stable.headOwners)
        val renumbered = alignOrigins(before, java(text, "Example$9"))
        assertEquals(stable.baseOwners, renumbered.baseOwners)
        assertEquals(mapOf("Example$9" to "Example$1"), renumbered.headOwners)
        assertEquals(before.single().syntax.body, java(text, "Example$9").single().syntax.body)
    }

    @Test fun `unmatched generated labels cannot collide with reserved actual declarations`() {
        val source = java("class Example { Object item = new Object() {}; }", "Example$1")
        val label = alignOrigins(source, emptyList()).baseOwners.values.single()
        val collision = alignOrigins(source, emptyList(), reservedOwners = setOf(label))
        assertTrue(collision.diagnostics.any { it.contains("collides") })
    }

    @Test fun `unique local contexts pair renamed compiler owners even when bodies change`() {
        val before = java("class Example { void run() { Object live = new Object() { int x = 1; }; } }", "Example\$1")
        val after = java("class Example { void run() { Object extra = new Object() {}; Object live = new Object() { int x = 2; }; } }",
            "Example\$1", "Example\$2")
        val result = alignOrigins(before, after)
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.baseOwners["Example\$1"], result.headOwners["Example\$2"])
        assertNotEquals(result.headOwners["Example\$1"], result.headOwners["Example\$2"])
    }

    @Test fun `uniquely unchanged body can move within one named method context`() {
        val before = java("class Example { Object run() { return new Object() { int x = 1; }; } }", "Example\$1")
        val after = java("class Example { Object run() { Object moved = new Object() { int x = 1; }; return moved; } }", "Example\$5")
        val result = alignOrigins(before, after)
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.baseOwners.values.single(), result.headOwners.values.single())
    }

    @Test fun `body equality alone never pairs origins from different named methods`() {
        val before = java("class Example { Object first() { return new Object() {}; } }", "Example\$1")
        val after = java("class Example { Object second() { return new Object() {}; } }", "Example\$1")
        val result = alignOrigins(before, after)
        assertNotEquals(result.baseOwners.values.single(), result.headOwners.values.single())
    }

    @Test fun `same-line arguments distinguish duplicate bodies without using compiler ordinals`() {
        val text = "class Example { void run() { use(new Object() {}, new Object() {}); } }"
        val result = alignOrigins(java(text, "Example\$1", "Example\$2"), java(text, "Example\$9", "Example\$8"))
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.baseOwners["Example\$1"], result.headOwners["Example\$9"])
        assertEquals(result.baseOwners["Example\$2"], result.headOwners["Example\$8"])
        assertEquals(2, result.baseOwners.values.toSet().size)
    }

    @Test fun `identical repeated statements remain explicitly ambiguous`() {
        val text = "class Example { void run() { use(new Object() {}); use(new Object() {}); } }"
        val result = alignOrigins(java(text, "Example\$1", "Example\$2"), java(text, "Example\$1", "Example\$2"))
        assertTrue(result.diagnostics.any { it.contains("Ambiguous") }, result.toString())
        assertTrue(result.baseOwners.values.toSet().intersect(result.headOwners.values.toSet()).isEmpty())
    }

    @Test fun `nested matching follows paired physical parents and preserves canonical ownership`() {
        val text = "class Example { Object outer = new Object() { Object inner = new Object() {}; }; }"
        val result = alignOrigins(java(text, "Example\$1", "Example\$1\$1"), java(text, "Example\$9", "Example\$9\$4"))
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.baseOwners["Example\$1"], result.headOwners["Example\$9"])
        assertEquals(result.baseOwners["Example\$1\$1"], result.headOwners["Example\$9\$4"])
        assertTrue(result.baseOwners.getValue("Example\$1\$1").startsWith(result.baseOwners.getValue("Example\$1") + "$"))
    }

    @Test fun `explicit file rename mapping permits source relocation`() {
        val text = "class Example { Object item = new Object() {}; }"
        val before = java(text, "Example\$1").map { it.copy(path = "old/Example.java") }
        val after = java(text, "Example\$9").map { it.copy(path = "new/Example.java") }
        val absent = alignOrigins(before, after)
        assertNotEquals(absent.baseOwners.values.single(), absent.headOwners.values.single())
        val mapped = alignOrigins(before, after, mapOf("old/Example.java" to "new/Example.java"))
        assertEquals(mapped.baseOwners.values.single(), mapped.headOwners.values.single())
    }

    @Test fun `inline copies sharing one physical origin cannot collapse separate emitted owners`() {
        val source = java("class Example { Object item = new Object() {}; }", "Example\$1").single()
        val result = alignOrigins(listOf(source), listOf(source.copy(owner = "Example\$2"), source.copy(owner = "Example\$3")))
        assertTrue(result.diagnostics.any { it.contains("copies") }, result.toString())
        assertEquals(2, result.headOwners.values.toSet().size)
        assertTrue(result.baseOwners.values.toSet().intersect(result.headOwners.values.toSet()).isEmpty())
    }

    @Test fun `duplicate owner and unavailable parent evidence remain diagnostic`() {
        val source = java("class Example { Object item = new Object() {}; }", "Example\$1").single()
        val duplicate = alignOrigins(listOf(source, source), listOf(source))
        assertTrue(duplicate.diagnostics.any { it.contains("duplicate compiler provenance") })
        val missing = source.copy(syntax = source.syntax.copy(parentRange = OriginSpan(0, 1)))
        assertTrue(alignOrigins(listOf(missing), listOf(source)).diagnostics.any { it.contains("parent origin") })
    }

    @Test fun `canonical names retain package and named nested owner prefix`() {
        val text = "package sample; class Example { class Nested { Object field = new Object() {}; } }"
        val result = alignOrigins(java(text, "sample.Example\$Nested\$1"), java(text, "sample.Example\$Nested\$2"))
        assertTrue(result.baseOwners.values.single().startsWith("sample.Example\$Nested\$"))
        assertEquals(result.baseOwners.values.single(), result.headOwners.values.single())
    }

    @Test fun `Kotlin object contexts pair independently of backend name mangling`() {
        val text = "package sample; class Example { fun run() { val callback = object { val x = 1 } } }"
        val syntax = originSyntax("Example.kt", text).single()
        val result = alignOrigins(listOf(SourceOrigin("sample.Example\$run\$1", "Example.kt", syntax)),
            listOf(SourceOrigin("sample.Example\$run\$2", "Example.kt", syntax)))
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(result.baseOwners.values.single(), result.headOwners.values.single())
    }

    @Test fun `inline definition and copies pair through distinct named caller contexts`() {
        val source = java("class Example { Object item = new Object() {}; }", "Example\$1").single()
        val contexts = listOf("Example.factory()", "Client.first()", "Client.second()")
        val before = contexts.mapIndexed { index, context ->
            source.copy(owner = "Example\$old$index", emissionContext = context)
        }
        val after = contexts.reversed().mapIndexed { index, context ->
            source.copy(owner = "Example\$new$index", emissionContext = context)
        }
        val result = alignOrigins(before, after)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(3, result.baseOwners.values.toSet().size)
        contexts.forEach { context ->
            assertEquals(result.baseOwners[before.single { it.emissionContext == context }.owner],
                result.headOwners[after.single { it.emissionContext == context }.owner])
        }
    }

    @Test fun `multiple inline copies in same named caller remain ambiguous`() {
        val source = java("class Example { Object item = new Object() {}; }", "Example\$1").single()
            .copy(emissionContext = "Client.run()")
        val result = alignOrigins(listOf(source), listOf(source.copy(owner = "Example\$2"),
            source.copy(owner = "Example\$3")))
        assertTrue(result.diagnostics.any { it.contains("copies") })
        assertTrue(result.baseOwners.values.toSet().intersect(result.headOwners.values.toSet()).isEmpty())
    }

    @Test fun `nested inline copies require explicit emitted parent identity`() {
        val sources = java("class Example { Object item = new Object() { Object child = new Object() {}; }; }",
            "Example\$1", "Example\$1\$1")
        val old = listOf(sources[0].copy(emissionContext = "Client.first()"),
            sources[0].copy(owner = "Example\$2", emissionContext = "Client.second()"),
            sources[1].copy(enclosingOriginOwner = "Example\$1"),
            sources[1].copy(owner = "Example\$2\$1", enclosingOriginOwner = "Example\$2"))
        // Child copies also need distinct stable emission context; the actual parent identity supplies nesting.
        val before = old.mapIndexed { index, source ->
            if (index < 2) source else source.copy(emissionContext = "Client.child${index - 2}()")
        }
        val after = before.map { it.copy(owner = it.owner + "x", enclosingOriginOwner = it.enclosingOriginOwner?.plus("x")) }
        val result = alignOrigins(before, after)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        before.zip(after).forEach { (left, right) -> assertEquals(result.baseOwners[left.owner], result.headOwners[right.owner]) }
        assertTrue(result.baseOwners.getValue("Example\$1\$1").startsWith(result.baseOwners.getValue("Example\$1") + "\$"))
        val absentParent = before.map { it.copy(enclosingOriginOwner = null) }
        assertTrue(alignOrigins(absentParent, after).diagnostics.any { it.contains("parent origin") })
    }

    @Test fun `top level Kotlin origins keep facade ownership when independently added or removed`() {
        val text = "package sample; val value = object {}"
        val syntax = originSyntax("Factories.kt", text).single()
        val source = SourceOrigin("sample.FactoriesKt\$value\$1", "Factories.kt", syntax)
        val removed = alignOrigins(listOf(source), emptyList())
        val added = alignOrigins(emptyList(), listOf(source))
        assertTrue(removed.diagnostics.isEmpty())
        assertTrue(added.diagnostics.isEmpty())
        assertTrue(removed.baseOwners.values.single().startsWith("sample.FactoriesKt$"))
        assertNotEquals(removed.baseOwners.values.single(), added.headOwners.values.single())
    }

    @Test fun `blank inline caller context is not evidence that duplicate copies are distinct`() {
        val origin = java("class Example { Object item = new Object() {}; }", "Example$1").single()
        val copies = listOf(origin.copy(emissionContext = " "), origin.copy(owner = "Example$2", emissionContext = "Client.run()"))
        val result = alignOrigins(copies, copies)
        assertTrue(result.diagnostics.any { it.contains("ambiguous emitted copies") })
    }

    @Test fun `added nested origin keeps its proven parent while receiving an independent identity`() {
        val before = java("class Example { Object outer = new Object() {}; }", "Example$1")
        val after = java("class Example { Object outer = new Object() { Object inner = new Object() {}; }; }",
            "Example$4", "Example$4$1")
        val result = alignOrigins(before, after)
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals("Example$1", result.headOwners["Example$4"])
        assertTrue(result.headOwners.getValue("Example$4$1").startsWith("Example$1$"))
        assertTrue(result.headOwners.getValue("Example$4$1") !in result.baseOwners.values)
    }

    private fun java(text: String, vararg owners: String): List<SourceOrigin> {
        val syntax = originSyntax("Example.java", text).sortedBy { it.range.start }
        assertEquals(owners.size, syntax.size, text)
        return owners.zip(syntax) { owner, origin -> SourceOrigin(owner, "Example.java", origin) }
    }
}
