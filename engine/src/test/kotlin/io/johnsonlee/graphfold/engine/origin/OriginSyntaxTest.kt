package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginSyntaxTest {
    @Test fun `class body excludes allocation arguments with nested constructor expressions`() {
        val examples = listOf(
            "Example.java" to "class Example { Object item = new Base(new int[]{1}) { String text = \"}\"; }; }",
            "Example.kt" to "class Example { val item = object : Base(intArrayOf(1)) { val text = \"}\" } }",
            "Example.java" to "enum Example { ITEM(new int[]{1}) { void run() {} }; Example(int[] x) {} }",
        )
        examples.forEach { (path, text) ->
            val syntax = originSyntax(path, text).single()
            val body = kotlin.test.assertNotNull(syntax.bodyRange)
            assertEquals('{', text[body.start])
            assertEquals('}', text[body.end - 1])
            assertTrue(body.start > text.indexOf("1"), text)
            assertTrue(body.start > syntax.range.start)
            assertEquals(syntax.range.end, body.end)
        }
    }

    @Test fun `changed anonymous bodies keep field anchors and ignore trivia`() {
        checkStable("Example.java", "class Example { Object first = new Object() { int x = 1; }; }",
            "class Example { /* note */ Object first = new Object() { int x = 2; }; }")
        checkStable("Example.kt", "class Example { val first = object { val x = 1 } }",
            "class Example { /* note */ val first = object { val x = 2 } }")
    }

    @Test fun `same-line identical expressions retain local variable identity`() {
        val cases = mapOf(
            "Example.java" to "class Example { void f() { Object first = new Object() {}; Object second = new Object() {}; } }",
            "Example.kt" to "class Example { fun f() { val first = object {}; val second = object {} } }",
        )
        cases.forEach { (path, text) ->
            val origins = originSyntax(path, text)
            assertEquals(2, origins.size)
            assertNotEquals(origins[0].anchor, origins[1].anchor)
            assertEquals(origins[0].body, origins[1].body)
            assertEquals(origins[0].container, origins[1].container)
        }
    }

    @Test fun `call argument positions distinguish identical expressions including Kotlin expression bodies`() {
        val cases = mapOf(
            "Example.java" to "class Example { void f() { pair(new Object() {}, new Object() {}); } }",
            "Example.kt" to "class Example { fun f() = pair(object {}, object {}) }",
        )
        cases.forEach { (path, text) ->
            val origins = originSyntax(path, text)
            assertEquals(2, origins.size)
            assertNotEquals(origins[0].anchor, origins[1].anchor)
            assertTrue(origins.all { it.anchor.contains("pair") })
        }
    }

    @Test fun `duplicate statement anchors remain duplicates rather than acquiring sequence identities`() {
        val cases = mapOf(
            "Example.java" to "class Example { void f() { use(new Object() {}); use(new Object() {}); } }",
            "Example.kt" to "class Example { fun f() { use(object {}); use(object {}) } }",
        )
        cases.forEach { (path, text) ->
            val origins = originSyntax(path, text)
            assertEquals(2, origins.size)
            assertEquals(origins[0].anchor, origins[1].anchor)
            assertEquals(origins[0].container, origins[1].container)
            assertNotEquals(origins[0].range, origins[1].range)
        }
    }

    @Test fun `nested origins point to physical parent ranges without generated names`() {
        val cases = mapOf(
            "Example.java" to "class Example { Object outer = new Object() { Object inner = new Object() {}; }; }",
            "Example.kt" to "class Example { val outer = object { val inner = object {} } }",
        )
        cases.forEach { (path, text) ->
            val origins = originSyntax(path, text)
            val outer = origins.single { it.parentRange == null }
            val inner = origins.single { it.parentRange != null }
            assertEquals(outer.range, inner.parentRange)
            assertTrue(inner.container.contains("field:outer"))
            assertTrue(inner.container.contains("field:inner"))
            assertTrue(origins.none { it.container.contains('$') })
        }
    }

    @Test fun `instance static and field initialization have distinct structural containers`() {
        val java = originSyntax("Example.java", """
            class Example {
                Object field = new Object() {};
                { Object action = new Object() {}; }
                static { Object action = new Object() {}; }
            }
        """.trimIndent())
        assertEquals(3, java.map { it.container }.toSet().size)
        assertTrue(java.any { it.container.endsWith("initializer:static") })
        assertTrue(java.any { it.container.endsWith("initializer:instance") })
        val kotlin = originSyntax("Example.kt", """
            class Example {
                val field = object {}
                init { val action = object {} }
                companion object { init { val action = object {} } }
            }
        """.trimIndent())
        assertEquals(3, kotlin.map { it.container }.toSet().size)
    }

    @Test fun `enum constant bodies have named entry contexts and actual syntax ranges`() {
        val text = "enum Choice { FIRST { int value() { return 1; } }, SECOND { int value() { return 2; } }; }"
        val origins = originSyntax("Choice.java", text)
        assertEquals(2, origins.size)
        assertEquals(1, originSyntax("Empty.java", "enum Empty { A {}, B }").size)
        assertTrue(origins.any { it.container.endsWith("enum-entry:FIRST") })
        assertTrue(origins.any { it.container.endsWith("enum-entry:SECOND") })
        origins.forEach { origin -> assertEquals(origin, originSyntax("Choice.java", text, origin.range.start, origin.range.end)) }
    }

    @Test fun `UTF16 CRLF and tabs preserve exact source offsets`() {
        val cases = mapOf(
            "Example.java" to "// 😀\r\nclass Example {\r\n\tObject value = new Object() {};\r\n}",
            "Example.kt" to "// 😀\r\nclass Example {\r\n\tval value = object {};\r\n}",
        )
        cases.forEach { (path, text) ->
            val expected = if (path.endsWith("java")) "new Object() {}" else "object {}"
            val origin = originSyntax(path, text).single()
            assertEquals(expected, text.substring(origin.range.start, origin.range.end))
            assertEquals(text.indexOf(expected), origin.range.start)
            assertNull(originSyntax(path, text, origin.range.start + 1, origin.range.end))
        }
    }

    @Test fun `malformed and unsupported inputs yield no guessed origins`() {
        assertTrue(originSyntax("Example.java", "class Example { Object x = new Object() {").isEmpty())
        assertTrue(originSyntax("Example.kt", "class Example { val x = object {").isEmpty())
        assertTrue(originSyntax("Example.txt", "new Object() {}").isEmpty())
        assertTrue(originSyntax("Example.java", "class Example { Object x = new Object(); }").isEmpty())
        assertTrue(originSyntax("Example.kt", "object Named {}").isEmpty())
    }

    @Test fun `Kotlin receiver overload constructors and default arguments keep named scopes`() {
        val text = """
            package sample
            class Example {
                constructor(value: Int) { consume(object {}) }
                fun String.make(value: List<String>): Any = object {}
                fun make(value: Any = object {}) { consume(value) }
            }
            val factory: (Int) -> Any = fun(value) = object {}
            val top = object {}
            object Registry { val entry = object {} }
        """.trimIndent()
        val origins = originSyntax("Example.kt", text)
        assertEquals(6, origins.size)
        assertTrue(origins.any { "constructor:(Int)" in it.container })
        assertTrue(origins.any { "method:String:make(List" in it.container })
        assertTrue(origins.any { "method::make(Any)" in it.container && "value" in it.anchor })
        assertTrue(origins.any { "field:top" in it.container })
        assertTrue(origins.any { "type:Registry/field:entry" in it.container })
        assertTrue(origins.any { "method::null()" in it.container })
    }

    @Test fun `Kotlin delegated supertypes retain call context without a containing member`() {
        val call = originSyntax("Example.kt", "class Example : Action by wrap(object : Action {})").single()
        assertTrue("wrap" in call.anchor)
        val direct = originSyntax("Example.kt", "class Example : Action by object : Action {}").single()
        assertEquals("<graphfold-origin>", direct.anchor)
        assertTrue(direct.container.endsWith("type:Example"))
    }

    @Test fun `Java constructors and empty input retain only actual anonymous allocations`() {
        val origins = originSyntax("Example.java", """
            package sample;
            class Example {
                Example(int value) { consume(new Object() {}); }
                Example(String value) { consume(new Object() {}); }
                void consume(Object x) {}
            }
        """.trimIndent())
        assertEquals(2, origins.size)
        assertTrue(origins.any { it.container.endsWith("constructor:(int)") })
        assertTrue(origins.any { it.container.endsWith("constructor:(String)") })
        assertTrue(originSyntax("Empty.java", "").isEmpty())
        assertTrue(originSyntax("Empty.kt", "").isEmpty())
    }

    @Test fun `anonymous enum constructor arguments retain the enum entry context`() {
        val text = "enum Example { ENTRY(new Object() {}); Example(Object value) {} }"
        val origin = originSyntax("Example.java", text).single()
        assertTrue("ENTRY" in origin.anchor)
        assertTrue(origin.anchor.contains("<graphfold-origin>"))
        assertEquals("new Object() {}", text.substring(origin.range.start, origin.range.end))
    }

    private fun checkStable(path: String, before: String, after: String) {
        val base = originSyntax(path, before).single()
        val head = originSyntax(path, after).single()
        assertEquals(base.anchor, head.anchor)
        assertEquals(base.container, head.container)
        assertNotEquals(base.body, head.body)
    }
}
