package io.johnsonlee.graphfold.compiler.javac

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OriginDigestTest {
    @Test fun `missing digest capability fails closed without a replacement origin hash`() {
        val missing = NoSuchAlgorithmException("SHA-256 provider unavailable")
        val failure = assertFailsWith<IllegalStateException> {
            OriginListener.sha256(byteArrayOf(1)) { throw missing }
        }
        assertSame(missing, failure.cause)
        assertEquals("JDK SHA-256 implementation missing", failure.message)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            OriginListener.sha256(byteArrayOf()) { MessageDigest.getInstance("SHA-256") })
    }
}
