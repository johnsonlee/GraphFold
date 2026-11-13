package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScopeRuleProbeTest {
    private val names = setOf("baseline [scope :unchanged]")
    private val pending = RuleAudit(names.single(), Revision.BASE, 0,
        RuleSelector(RuleSelectorKind.CYPHER, "MATCH (c:CallSite) RETURN c", "select calls"))

    @Test fun `a completed probe records no graph diagnostic but missing receipts remain blocking`() {
        assertNull(scopeRuleProbe(names, listOf(pending.copy(matchedCallCount = 0))) {})
        assertNull(scopeRuleProbe(names, emptyList()) {})
        val diagnostic = scopeRuleProbe(names, listOf(pending)) {}
        assertEquals(DiagnosticCode.INCOMPLETE_GRAPH, diagnostic?.code)
        assertTrue(diagnostic!!.message.contains("rule matching did not complete"))
    }

    @Test fun `unobserved selection keeps the original failure while observed counts survive later failure`() {
        val failure = IllegalStateException("selection evidence unavailable")
        val diagnostic = scopeRuleProbe(names, listOf(pending)) { throw failure }
        assertTrue(diagnostic!!.message.contains("selection evidence unavailable"))
        assertNull(scopeRuleProbe(names, listOf(pending.copy(matchedCallCount = 1))) { throw failure })
    }

    @Test fun `message-less selection failures retain an explicit unknown-match diagnostic`() {
        assertTrue(scopeRuleProbe(names, listOf(pending)) { throw IllegalStateException() }!!
            .message.contains("rule matching did not complete"))
    }

    @Test fun `cancelling a probe propagates interruption to the SDK boundary`() {
        val interruption = InterruptedException("cancelled")
        assertSame(interruption, assertFailsWith<InterruptedException> {
            scopeRuleProbe(names, listOf(pending)) { throw interruption }
        })
    }
}
