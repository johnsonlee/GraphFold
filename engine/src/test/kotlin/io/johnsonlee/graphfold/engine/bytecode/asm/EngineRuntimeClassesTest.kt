package io.johnsonlee.graphfold.engine.bytecode.asm

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineRuntimeClassesTest {
    private val owner = "kotlin/jvm/internal/Intrinsics"

    @Test fun `runtime trust requires a present reference and one byte identical supplied definition`() {
        val reference = requireNotNull(Unit::class.java.getResourceAsStream("/$owner.class")).use { it.readBytes() }
        assertContentEquals(reference, EngineRuntimeClasses.requireBytes(owner))
        assertTrue(EngineRuntimeClasses.matches(owner, listOf(reference)))
        assertFalse(EngineRuntimeClasses.matches(owner, emptyList()))
        assertFalse(EngineRuntimeClasses.matches(owner, listOf(reference, reference)))
        assertFalse(EngineRuntimeClasses.matches(owner, listOf(reference + byteArrayOf(0))))
    }

    @Test fun `missing engine resource cannot be trusted or replaced by supplied bytes`() {
        val missing = "absent/graphfold/RuntimeReference"
        assertNull(EngineRuntimeClasses.bytes(missing))
        assertFalse(EngineRuntimeClasses.matches(missing, listOf(byteArrayOf(1))))
        val failure = assertFailsWith<IllegalArgumentException> { EngineRuntimeClasses.requireBytes(missing) }
        assertTrue(missing in failure.message.orEmpty())
    }
}
