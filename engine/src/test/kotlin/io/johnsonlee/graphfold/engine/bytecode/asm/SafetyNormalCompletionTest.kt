package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.PropertyProof
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Domain mechanics: independent status combinations, not engine-produced safety certificates. */
class SafetyNormalCompletionTest {
    @Test fun `normal completion requires both nonthrowing and terminating proofs independently`() {
        for (exceptions in ProofStatus.entries) for (termination in ProofStatus.entries) {
            val facts = SafetyFacts(
                exceptions = PropertyProof(exceptions, listOf("Domain status combination")),
                termination = PropertyProof(termination, listOf("Domain status combination")),
            )
            assertEquals(exceptions == ProofStatus.PROVEN && termination == ProofStatus.PROVEN,
                facts.normalCompletionProven, "$exceptions / $termination")
        }
    }
}
