package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoldDocumentTest {
    private val rule = "{select: 'MATCH (c:CallSite) RETURN c', value: false}"

    @Test
    fun `single document becomes a baseline and retains actual Graphite rules`() {
        val scenarios = FoldDocument.parse("version: 1\nfolds: [$rule]", 4)
        assertEquals(listOf("baseline"), scenarios.map { it.name })
        assertEquals(1, scenarios.single().rules.size)
    }

    @Test
    fun `explicit scenarios are preserved as complete independent assumptions`() {
        val scenarios = FoldDocument.parse("""
            scenarios:
              - name: A
                version: 1
                folds: [$rule, $rule]
              - name: NA
                folds: [$rule]
        """.trimIndent(), 2)
        assertEquals(listOf("A", "NA"), scenarios.map { it.name })
        assertEquals(listOf(2, 1), scenarios.map { it.rules.size })
    }

    @Test
    fun `single YAML document accepts markers comments and Graphite match rules`() {
        val documents = listOf(
            "---\nfolds: [$rule]\n...\n# trailing comment\n",
            "# leading comment\nfolds: [$rule]\n# trailing comment\n",
            "folds: [{match: {CallSite: {callee_class: example.Flags, callee_name: enabled}}, value: false}]",
            "folds: [{select: 'MATCH (c:CallSite) RETURN c', value: null}]",
        )
        documents.forEach { document -> assertEquals(1, FoldDocument.parse(document, 1).single().rules.size) }
    }

    @Test
    fun `additional YAML documents cannot be silently ignored`() {
        for (trailing in listOf("folds: [$rule]", "null", "[]", "42", "", "invalid: [")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                FoldDocument.parse("folds: [$rule]\n---\n$trailing", 1)
            }
            assertTrue(failure.message.orEmpty().contains("Invalid fold YAML"), failure.message)
        }
    }

    @Test
    fun `invalid syntax and duplicate nested properties report YAML diagnostics`() {
        val invalid = listOf(
            "folds: [", "folds: [$rule]\nfolds: [$rule]",
            "folds: [{select: first, select: second, value: false}]",
            "scenarios: [{name: A, name: B, folds: [$rule]}]",
        )
        invalid.forEach { document ->
            val failure = assertFailsWith<IllegalArgumentException> { FoldDocument.parse(document, 1) }
            assertTrue(failure.message.orEmpty().contains("Invalid fold YAML"), failure.message)
        }
    }

    @Test
    fun `empty and nonobject roots are rejected explicitly`() {
        for (document in listOf("null", "~", "false", "42", "a scalar", "[]")) {
            val failure = assertFailsWith<IllegalArgumentException> { FoldDocument.parse(document, 1) }
            assertTrue(failure.message.orEmpty().contains("root must be an object"), failure.message)
        }
        for (document in listOf("", "# only a comment")) {
            val failure = assertFailsWith<IllegalArgumentException> { FoldDocument.parse(document, 1) }
            assertTrue(failure.message.orEmpty().contains("Invalid fold YAML"), failure.message)
        }
    }

    @Test
    fun `Graphite schema failures identify the scenario and rule index`() {
        val invalidRules = listOf(
            "{select: 'MATCH (c) RETURN c', value: false, typo: 1}",
            "{select: 1, value: false}", "{select: '', value: false}",
            "{select: 'MATCH (c) RETURN c'}", "{match: {}, value: false}",
            "{match: {UnknownNode: {}}, value: false}",
            "{match: {CallSite: {callee_name: enabled}}, value: false, typo: 1}",
            "{select: 'MATCH (c) RETURN c', value: [1, 2]}",
            "{match: {CallSite: {callee_typo: enabled}}, value: false}",
            "{select: 'MATCH (c) RETURN c', args: {-1: flag}, value: false}",
            "{select: 'MATCH (c) RETURN c', selected: 1, value: false}",
            "{select: 'MATCH (c) RETURN c', value: {UnknownConstant: {value: 1}}}",
        )
        invalidRules.forEach { invalid ->
            val failure = assertFailsWith<IllegalArgumentException> {
                FoldDocument.parse("scenarios: [{name: control, folds: [$rule, $invalid]}]", 1)
            }
            assertTrue(failure.message.orEmpty().contains("scenarios[0].folds[1]"), failure.message)
        }
        val scalar = assertFailsWith<IllegalArgumentException> { FoldDocument.parse("folds: [1]", 1) }
        assertTrue(scalar.message.orEmpty().contains("fold document.folds[0] must be an object"))
    }

    @Test
    fun `invalid or ambiguous fold documents cannot silently fall back to no folding`() {
        val invalid = listOf(
            "scenarios: []", "scenarios: nope", "scenarios: [1]", "scenarios: []\nextra: 1",
            "folds: []", "folds: nope", "folds: [1]", "folds: [$rule]\nversion: 2",
            "folds: [$rule]\nextra: 1", "name: ignored\nfolds: [$rule]", "scenarios: [{folds: [$rule]}]",
            "scenarios: [{name: '', folds: [$rule]}]",
            "scenarios: [{name: same, folds: [$rule]}, {name: same, folds: [$rule]}]",
            "folds: [$rule]\nfolds: [$rule]", "folds: [{select: 'MATCH (c) RETURN c'}]",
        )
        invalid.forEach { document -> assertFails("accepted invalid document: $document") { FoldDocument.parse(document, 4) } }
        assertFails { FoldDocument.parse("folds: [$rule]", 0) }
        val limited = "scenarios: [{name: A, folds: [$rule]}, {name: NA, folds: [$rule]}]"
        assertFails { FoldDocument.parse(limited, 1) }
        assertTrue(FoldDocument.parse("folds: [$rule]", 1).isNotEmpty())
    }
}
