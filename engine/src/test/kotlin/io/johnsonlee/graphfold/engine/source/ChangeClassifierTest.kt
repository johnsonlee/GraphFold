package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.ChangeKind
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChangeClassifierTest {
    @Test
    fun `Java field changes distinguish addition deletion initializer and rename`() {
        assertContains(classify("class A {}", "class A { int x = 1; }"), ChangeKind.FIELD_ADDED)
        assertContains(classify("class A { int x = 1; }", "class A {}"), ChangeKind.FIELD_REMOVED)
        assertContains(classify("class A { int x = 1; }", "class A { int x = 2; }"), ChangeKind.FIELD_INITIALIZER_CHANGED)
        assertContains(classify("class A { int x = 1; }", "class A { int y = 1; }"), ChangeKind.FIELD_RENAMED)
        val changed = classify("class A { int x = 1; }", "class A { int y = 2; }")
        assertFalse(ChangeKind.FIELD_RENAMED in changed)
        assertContains(changed, ChangeKind.FIELD_ADDED)
    }

    @Test
    fun `Java conditions distinguish conjunction additions removals and operator changes`() {
        fun code(condition: String) = "class A { int f(boolean a, boolean b) { if ($condition) return 1; return 0; } }"
        assertContains(classify(code("a"), code("a && b")), ChangeKind.CONDITION_ADDED)
        assertContains(classify(code("a && b"), code("a")), ChangeKind.CONDITION_REMOVED)
        assertContains(classify(code("a && b"), code("a || b")), ChangeKind.CONDITION_CHANGED)
        assertContains(classify("class A { void f() { a(); } }", "class A { void f() { b(); } }"), ChangeKind.CALL_CHANGED)
    }

    @Test
    fun `method and type changes include strict one to one rename candidates`() {
        assertContains(classify("class A {}", "class A { int f() { return 1; } }"), ChangeKind.METHOD_ADDED)
        assertContains(classify("class A { int f() { return 1; } }", "class A {}"), ChangeKind.METHOD_REMOVED)
        assertContains(classify("class A { int f() { return 1; } }", "class A { int g() { return 1; } }"), ChangeKind.METHOD_RENAMED)
        val changed = classify("class A { int f() { return 1; } }", "class A { int g() { return 2; } }")
        assertFalse(ChangeKind.METHOD_RENAMED in changed)
        assertContains(classify("class A {}", "class B {}"), ChangeKind.TYPE_ADDED)
        assertContains(classify("class A {}", "class B {}"), ChangeKind.TYPE_REMOVED)
    }

    @Test
    fun `Kotlin field functions predicates calls and renames use PSI`() {
        assertContains(classify("class A", "class A { val x = 1 }", true), ChangeKind.FIELD_ADDED)
        assertContains(classify("val x = 1", "val x = 2", true), ChangeKind.FIELD_INITIALIZER_CHANGED)
        assertContains(classify("val x = 1", "val y = 1", true), ChangeKind.FIELD_RENAMED)
        assertContains(classify("fun f() = 1", "fun g() = 1", true), ChangeKind.METHOD_RENAMED)
        assertFalse(ChangeKind.METHOD_RENAMED in classify("fun f() = 1", "fun g() = 2", true))
        assertContains(classify("fun f(a:Boolean,b:Boolean) = if(a) 1 else 0",
            "fun f(a:Boolean,b:Boolean) = if(a && b) 1 else 0", true), ChangeKind.CONDITION_ADDED)
        assertContains(classify("fun f() = a()", "fun f() = b()", true), ChangeKind.CALL_CHANGED)
    }

    @Test
    fun `classification never attributes unrelated changes outside the supplied diff`() {
        val before = source("class A {\n int x = 1;\n int f() { return 1; }\n}", false)
        val after = source("class A {\n int x = 2;\n int f() { return 2; }\n}", false)
        val locations = listOf(Revision.BASE, Revision.HEAD).map {
            SourceLocation(it, before.path, SourceRange(3, 3), SourceRange(2, 23))
        }
        assertEquals(setOf(ChangeKind.BODY_CHANGED), ChangeClassifier.classify(before, after, locations))
        assertEquals(emptySet(), ChangeClassifier.classify(before, after,
            listOf(SourceLocation(Revision.HEAD, "Other.java", SourceRange(1, 1), SourceRange(1, 2)))))
    }

    @Test
    fun `comments only are source changes and unsupported syntax has conservative fallback`() {
        assertEquals(setOf(ChangeKind.SOURCE_ONLY), classify("class A {}", "// hello\nclass A {}"))
        assertEquals(setOf(ChangeKind.SOURCE_ONLY), classify("fun f() = 1", "// hello\nfun f() = 1", true))
        assertContains(classify("class A {", "class A {{"), ChangeKind.BODY_CHANGED)
    }

    @Test
    fun `static Java and Kotlin object initializers receive initialization labels`() {
        assertContains(classify("class A { static int x = 1; }", "class A { static int x = 2; }"),
            ChangeKind.CLASS_INITIALIZATION_CHANGED)
        assertContains(classify("class A { static { a(); } }", "class A { static { b(); } }"),
            ChangeKind.CLASS_INITIALIZATION_CHANGED)
        assertContains(classify("object A { val x = 1 }", "object A { val x = 2 }", true),
            ChangeKind.CLASS_INITIALIZATION_CHANGED)
        assertContains(classify("object A { init { a() } }", "object A { init { b() } }", true),
            ChangeKind.CLASS_INITIALIZATION_CHANGED)
        assertContains(classify("val x = 1", "val x = 2", true), ChangeKind.CLASS_INITIALIZATION_CHANGED)
    }

    @Test
    fun `loop conditional expression switch and when selector changes are labeled`() {
        listOf(
            "while (a) {}" to "while (!a) {}",
            "do {} while (a);" to "do {} while (!a);",
            "for (; a;) {}" to "for (; !a;) {}",
            "int x = a ? 1 : 0;" to "int x = !a ? 1 : 0;",
            "switch (a ? 1 : 0) { default: break; }" to "switch (a ? 2 : 0) { default: break; }",
        ).forEach { (before, after) ->
            assertContains(classify("class A { void f(boolean a) { $before } }",
                "class A { void f(boolean a) { $after } }"), ChangeKind.CONDITION_CHANGED)
        }
        assertContains(classify("fun f(x: Int) = when(x) { 1 -> 0; else -> 1 }",
            "fun f(x: Int) = when(x) { 2 -> 0; else -> 1 }", true), ChangeKind.CONDITION_CHANGED)
        assertContains(classify("fun f(a: Boolean) { while(a) {} }",
            "fun f(a: Boolean) { while(!a) {} }", true), ChangeKind.CONDITION_CHANGED)
    }

    @Test
    fun `ambiguous renames are not asserted and modifiers are declarations`() {
        val ambiguous = classify("class A { int x = 1; int y = 1; }", "class A { int z = 1; int w = 1; }")
        assertFalse(ChangeKind.FIELD_RENAMED in ambiguous)
        assertContains(ambiguous, ChangeKind.FIELD_ADDED)
        assertContains(classify("class A { int x; }", "class A { final int x; }"), ChangeKind.DECLARATION_CHANGED)
        assertContains(classify("class A { int f() { return 1; } }",
            "class A { public int f() { return 1; } }"), ChangeKind.DECLARATION_CHANGED)
        assertContains(classify("class A {}", "final class A {}"), ChangeKind.DECLARATION_CHANGED)
    }

    @Test
    fun `added and deleted sources use only the available revision`() {
        val added = source("class A { int x = 1; }", false)
        assertContains(ChangeClassifier.classify(null, added, listOf(full(added, Revision.HEAD))), ChangeKind.TYPE_ADDED)
        assertContains(ChangeClassifier.classify(added, null, listOf(full(added, Revision.BASE))), ChangeKind.TYPE_REMOVED)
        assertEquals(emptySet(), ChangeClassifier.classify(null, null, emptyList()))
        assertContains(classify("class A { void f() {} }", "class A { void f() { if (true) return; } }"),
            ChangeKind.CONDITION_ADDED)
        assertContains(classify("class A { void f() { if (true) return; } }", "class A { void f() {} }"),
            ChangeKind.CONDITION_REMOVED)
    }

    @Test
    fun `rename pairing spans revisions even when obligation belongs to only the old identity`() {
        val before = source("class A {\n int oldName() { return 1; }\n int untouched() { return 2; }\n}", false)
        val after = source("class A {\n int newName() { return 1; }\n int untouched() { return 3; }\n}", false)
        val removed = SourceLocation(Revision.BASE, before.path, SourceRange(2, 2), SourceRange(6, 13))
        assertContains(ChangeClassifier.classify(before, after, listOf(removed)), ChangeKind.METHOD_RENAMED)
        val unrelated = SourceLocation(Revision.HEAD, after.path, SourceRange(3, 3), SourceRange(27, 28))
        assertEquals(setOf(ChangeKind.BODY_CHANGED), ChangeClassifier.classify(before, after, listOf(unrelated)))
    }

    @Test
    fun `static blocks and static field initialization additions and removals retain execution labels`() {
        listOf("static { register(); }", "static int value = load();").forEach { declaration ->
            assertContains(classify("class A {}", "class A { $declaration }"), ChangeKind.CLASS_INITIALIZATION_CHANGED)
            assertContains(classify("class A { $declaration }", "class A {}"), ChangeKind.CLASS_INITIALIZATION_CHANGED)
        }
        assertFalse(ChangeKind.CLASS_INITIALIZATION_CHANGED in
            classify("class A {}", "class A { static int value; }"))
        assertFalse(ChangeKind.CLASS_INITIALIZATION_CHANGED in
            classify("class A { { first(); } }", "class A { { second(); } }"))
    }

    @Test
    fun `calls added and removed without replacing another call remain call changes`() {
        listOf(false, true).forEach { kotlin ->
            val empty = if (kotlin) "fun f() {}" else "class A { void f() {} }"
            val call = if (kotlin) "fun f() { record() }" else "class A { void f() { record(); } }"
            assertContains(classify(empty, call, kotlin), ChangeKind.CALL_CHANGED)
            assertContains(classify(call, empty, kotlin), ChangeKind.CALL_CHANGED)
        }
    }

    @Test
    fun `renames across different declaring types are additions and removals`() {
        val result = classify("class A { int oldName() { return 1; } } class B {}",
            "class A {} class B { int newName() { return 1; } }")
        assertFalse(ChangeKind.METHOD_RENAMED in result)
        assertContains(result, ChangeKind.METHOD_ADDED)
        assertContains(result, ChangeKind.METHOD_REMOVED)
    }

    @Test
    fun `unrelated rename does not leak into a changed method obligation`() {
        val before = source("class A {\n int oldName() { return 1; }\n int target() { return 2; }\n}", false)
        val after = source("class A {\n int newName() { return 1; }\n int target() { return 3; }\n}", false)
        val target = SourceLocation(Revision.HEAD, after.path, SourceRange(3, 3), SourceRange(24, 25))
        assertEquals(setOf(ChangeKind.BODY_CHANGED), ChangeClassifier.classify(before, after, listOf(target)))
    }

    @Test
    fun `Kotlin local values and instance initialization are not static field changes`() {
        val local = classify("fun f() { val value = 1 }", "fun f() { val value = 2 }", true)
        assertEquals(setOf(ChangeKind.BODY_CHANGED), local)
        val instance = classify("class A { init { first() } }", "class A { init { second() } }", true)
        assertContains(instance, ChangeKind.CALL_CHANGED)
        assertFalse(ChangeKind.CLASS_INITIALIZATION_CHANGED in instance)
        assertContains(classify("class A { var value: Int = 1 }", "class A { val value: Int = 1 }", true),
            ChangeKind.DECLARATION_CHANGED)
        assertFalse(ChangeKind.CLASS_INITIALIZATION_CHANGED in
            classify("object A { lateinit var value: String }", "object A { lateinit var renamed: String }", true))
    }

    @Test
    fun `Kotlin object initialization insertion and removal are explicit`() {
        listOf("init { register() }", "val value = load()").forEach { declaration ->
            assertContains(classify("object A {}", "object A { $declaration }", true),
                ChangeKind.CLASS_INITIALIZATION_CHANGED)
            assertContains(classify("object A { $declaration }", "object A {}", true),
                ChangeKind.CLASS_INITIALIZATION_CHANGED)
        }
    }

    @Test
    fun `Kotlin disjunction removal and arithmetic predicate changes distinguish term count`() {
        assertContains(classify("fun f(a: Boolean, b: Boolean) = if (a || b) 1 else 0",
            "fun f(a: Boolean, b: Boolean) = if (a) 1 else 0", true), ChangeKind.CONDITION_REMOVED)
        assertContains(classify("fun f(x: Int) = if (x + 1 > 2) 1 else 0",
            "fun f(x: Int) = if (x + 2 > 2) 1 else 0", true), ChangeKind.CONDITION_CHANGED)
        assertContains(classify("class A { int f(int x) { return switch(x) { default -> 1; }; } }",
            "class A { int f(int x) { return switch(x + 1) { default -> 1; }; } }"), ChangeKind.CONDITION_CHANGED)
        assertEquals(setOf(ChangeKind.BODY_CHANGED), classify("class A { void f() { for (;;) { int x = 1; } } }",
            "class A { void f() { for (;;) { int x = 2; } } }"))
    }

    @Test
    fun `malformed Kotlin cannot be treated as an equivalent source-only edit`() {
        assertEquals(setOf(ChangeKind.BODY_CHANGED), classify("fun broken(", "fun broken(", true))
    }

    @Test
    fun `duplicate declarations never imply a unique declaration modification`() {
        val result = classify("class A { int value; int value; }", "class A { long value; long value; }")
        assertEquals(setOf(ChangeKind.BODY_CHANGED), result)
    }

    @Test
    fun `out of range fragments use conservative fallback rather than guessing a declaration`() {
        val before = source("class A {}", false)
        val after = source("class B {}", false)
        val location = SourceLocation(Revision.HEAD, after.path, SourceRange(5, 6), SourceRange(1, 100))
        assertEquals(setOf(ChangeKind.BODY_CHANGED), ChangeClassifier.classify(before, after, listOf(location)))
    }

    @Test
    fun `comment-only added and deleted files use fallback without inventing declarations`() {
        listOf(false, true).forEach { kotlin ->
            val comment = source("// new file with no executable declarations", kotlin)
            assertEquals(setOf(ChangeKind.BODY_CHANGED),
                ChangeClassifier.classify(null, comment, listOf(full(comment, Revision.HEAD))))
            assertEquals(setOf(ChangeKind.BODY_CHANGED),
                ChangeClassifier.classify(comment, null, listOf(full(comment, Revision.BASE))))
            val unrelated = SourceLocation(Revision.HEAD, "Other.java", SourceRange(1, 1), SourceRange(1, 2))
            assertEquals(emptySet(), ChangeClassifier.classify(null, comment, listOf(unrelated)))
            assertEquals(emptySet(), ChangeClassifier.classify(comment, null, listOf(unrelated)))
        }
    }

    @Test
    fun `merging indistinguishable fields while deleting a method is not a unique rename`() {
        val changes = classify("class A { int x = 1; int y = 1; void obsolete() {} }", "class A { int merged = 1; }")
        assertFalse(ChangeKind.FIELD_RENAMED in changes)
        assertContains(changes, ChangeKind.FIELD_REMOVED)
        assertContains(changes, ChangeKind.FIELD_ADDED)
        assertContains(changes, ChangeKind.METHOD_REMOVED)
    }

    @Test
    fun `Kotlin comments inside declaration headers are source only`() {
        assertEquals(setOf(ChangeKind.SOURCE_ONLY), classify("fun f(): Int = 1",
            "fun /* explanation */ f(): Int = 1", true))
        assertEquals(setOf(ChangeKind.SOURCE_ONLY), classify("class A {}",
            "class /* explanation */ A {}", true))
    }

    @Test
    fun `Kotlin anonymous object and inferred anonymous function calls retain change labels`() {
        assertContains(classify("val handler = object { fun run() = first() }",
            "val handler = object { fun run() = second() }", true), ChangeKind.CALL_CHANGED)
        assertContains(classify("val callback: (Int) -> Unit = fun(value) { first(value) }",
            "val callback: (Int) -> Unit = fun(value) { second(value) }", true), ChangeKind.CALL_CHANGED)
    }

    private fun classify(before: String, after: String, kotlin: Boolean = false): Set<ChangeKind> {
        val old = source(before, kotlin)
        val new = source(after, kotlin)
        return ChangeClassifier.classify(old, new, listOf(full(old, Revision.BASE), full(new, Revision.HEAD)))
    }

    private fun source(text: String, kotlin: Boolean) = IndexedSource(
        if (kotlin) "Example.kt" else "Example.java", text, emptyList(), emptyList(),
    )

    private fun full(source: IndexedSource, revision: Revision) = SourceLocation(
        revision, source.path, SourceRange(1, source.text.count { it == '\n' } + 1),
        SourceRange(1, source.text.substringAfterLast('\n').length + 1),
    )
}
