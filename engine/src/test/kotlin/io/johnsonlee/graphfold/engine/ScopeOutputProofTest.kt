package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.git.changedFile
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ScopeOutputProofTest {
    private val source = changedFile("src/main/java/App.java", "src/main/java/App.java")
    private val base = project(ContentInventory(mapOf("class/App.class" to "before"), mapOf("resource" to "before")))
    private val head = project(ContentInventory(mapOf("class/App.class" to "after"), mapOf("resource" to "after")))

    @Test fun `source driven packaged resource effects get an independent output proof without replacing declarations`() {
        val classification = InputChangeProof(emptyList(), base.production, head.production)
        val proof = scopeOutputProof(classification, base, head, setOf(source))
        assertNotSame(classification, proof)
        assertEquals(listOf(source), proof.changes)
        assertEquals(emptySet(),
            proof.reasons(ReasonCode.ANALYSIS_INCOMPLETE, "baseline", "resource changed").single().changes)
    }

    @Test fun `existing input proof empty probes unchanged artifacts and source only compilation need no duplicate output obligations`() {
        val inputs = InputChangeProof(listOf(source), base.production, head.production)
        assertSame(inputs, scopeOutputProof(inputs, base, head, setOf(source)))
        val probe = InputChangeProof(emptyList(), base.production, head.production)
        assertSame(probe, scopeOutputProof(probe, base, head, emptySet()))
        val classOnly = head.copy(production = requireNotNull(head.production).copy(
            nonClassEntries = requireNotNull(base.production).nonClassEntries))
        val ordinary = InputChangeProof(emptyList(), base.production, classOnly.production)
        assertNotSame(ordinary, scopeOutputProof(ordinary, base, classOnly, setOf(source)))
        val unchanged = InputChangeProof(emptyList(), base.production, base.production)
        assertSame(unchanged, scopeOutputProof(unchanged, base, base, setOf(source)))
        val missing = InputChangeProof(emptyList(), null, null)
        assertSame(missing, scopeOutputProof(missing, base.copy(production = null), head.copy(production = null), setOf(source)))
    }

    private fun project(inventory: ContentInventory): StaticScope =
        StaticScope(Path.of("classes"), emptyList(), emptyList(), production = inventory)
}
