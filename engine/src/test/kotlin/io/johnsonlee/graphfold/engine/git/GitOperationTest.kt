package io.johnsonlee.graphfold.engine.git

import java.io.IOException
import org.eclipse.jgit.errors.TransportException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitOperationTest {
    @Test
    fun `transport failure removes credentials from message causes and suppressed errors`() {
        val secret = "https://fixture-user:fixture-secret@example.invalid/private.git"
        val failure = TransportException("Fetch failed for $secret", IOException("Credential $secret"))
        failure.addSuppressed(IOException("Retry failed for $secret"))

        val exposed = assertFailsWith<GitOperationException> { gitOperation<Unit> { throw failure } }

        assertEquals("Git operation failed", exposed.message)
        assertNull(exposed.cause)
        assertTrue(exposed.suppressed.isEmpty())
        assertFalse(exposed.stackTraceToString().contains("fixture-secret"))
        assertFalse(exposed.stackTraceToString().contains("example.invalid"))
    }

    @Test
    fun `existing deliberate failure retains its safe message and identity`() {
        val failure = GitOperationException("Submodule inputs are not supported")
        val exposed = assertFailsWith<GitOperationException> { gitOperation<Unit> { throw failure } }
        assertSame(failure, exposed)
    }

    @Test
    fun `interrupted exception is standardized and restores the interrupt flag`() {
        try {
            val exposed = assertFailsWith<InterruptedException> {
                gitOperation<Unit> { throw InterruptedException("transport-specific details") }
            }
            assertEquals("Git operation interrupted", exposed.message)
            assertNull(exposed.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `transport exception during cancellation is reported as interruption`() {
        try {
            val exposed = assertFailsWith<InterruptedException> {
                gitOperation<Unit> {
                    Thread.currentThread().interrupt()
                    throw IOException("transport-specific details")
                }
            }
            assertEquals("Git operation interrupted", exposed.message)
            assertNull(exposed.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `preexisting cancellation prevents the operation from starting`() {
        var invoked = false
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { gitOperation { invoked = true } }
            assertFalse(invoked)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `JVM errors propagate unchanged`() {
        val failure = LinkageError("fixture linkage failure")
        val exposed = assertFailsWith<LinkageError> { gitOperation<Unit> { throw failure } }
        assertSame(failure, exposed)
    }

    @Test
    fun `cancellation does not hide a JVM error raised by the operation`() {
        val failure = LinkageError("fixture linkage failure")
        try {
            val exposed = assertFailsWith<LinkageError> {
                gitOperation<Unit> {
                    Thread.currentThread().interrupt()
                    throw failure
                }
            }
            assertSame(failure, exposed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
