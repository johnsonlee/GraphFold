package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
    fun `invalid or ambiguous fold documents cannot silently fall back to no folding`() {
        val invalid = listOf(
            "scenarios: []", "scenarios: nope", "scenarios: [1]", "scenarios: []\nextra: 1",
            "folds: []", "folds: nope", "folds: [1]", "folds: [$rule]\nversion: 2",
            "folds: [$rule]\nextra: 1", "scenarios: [{folds: [$rule]}]",
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
