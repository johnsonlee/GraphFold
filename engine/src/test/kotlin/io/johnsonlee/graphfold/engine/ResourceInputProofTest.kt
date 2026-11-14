package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.bytecode.sootup.resourceReads

import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResourceInputProofTest {
    @Test fun `unknown inventories runtime consumers and new file presence cannot use key proof`() {
        val base = inventory("before")
        val head = inventory("after")
        listOf(null to head, base to null, base.copy(resourceConsumersKnown = false) to head,
            base to head.copy(resourceConsumersKnown = false), base to head.copy(resourceEntries = emptyMap())).forEach { (old, new) ->
            assertFalse(InputChangeProof(emptyList(), old, new).resourcesUnreachable(emptyList()))
        }
    }

    @Test fun `constant paths keys locale bundles deletions and unknown reads are checked against both revisions`() {
        val proof = InputChangeProof(emptyList(), inventory("before"), inventory("after"))
        assertTrue(proof.resourcesUnreachable(emptyList()))
        assertTrue(proof.resourcesUnreachable(methods(ResourceRead("elsewhere", null))))
        assertTrue(proof.resourcesUnreachable(methods(ResourceRead("messages", "unchanged"))))
        listOf(ResourceRead("messages", "changed"), ResourceRead("messages", null), ResourceRead(null, "changed"),
            ResourceRead(null, null)).forEach { assertFalse(proof.resourcesUnreachable(methods(it)), it.toString()) }
        assertNotNull(proof.gap())
        assertNull(proof.gap(resourcesProved = true))
        val locale = InputChangeProof(emptyList(), inventory("before", "messages_en_US.properties"),
            inventory("after", "messages_en_US.properties"))
        assertFalse(locale.resourcesUnreachable(methods(ResourceRead("messages", "changed"))))
        val removed = inventory("after").copy(resourceEntries = mapOf("messages.properties" to mapOf("unchanged" to "constant")))
        val deletion = InputChangeProof(emptyList(), inventory("before"), removed)
        assertFalse(deletion.resourcesUnreachable(methods(ResourceRead("messages", "changed"))))
    }

    @Test fun `comment only changes remain visible to byte readers but not constant key consumers`() {
        val base = inventory("before")
        val head = inventory("after").copy(resourceEntries = base.resourceEntries)
        val proof = InputChangeProof(emptyList(), base, head)
        assertTrue(proof.resourcesUnreachable(methods(ResourceRead("messages", "changed"))))
        assertFalse(proof.resourcesUnreachable(methods(ResourceRead(null, null))))
        assertTrue(InputChangeProof(emptyList(), base, base).resourcesUnreachable(methods(ResourceRead(null, null))))
    }

    private fun inventory(value: String, path: String = "messages.properties"): ContentInventory {
        val entries = mapOf("resource/0/$path" to value)
        return ContentInventory(entries, entries, resourceEntries = mapOf(path to mapOf("changed" to value, "unchanged" to "constant")),
            resourceIndependentEntries = mapOf("resource/0/$path" to "properties:$path"), resourceConsumersKnown = true)
    }

    private fun methods(read: ResourceRead) = listOf(
        MethodSnapshot("Sample.run()V", "", emptySet(), emptySet(), emptySet(), resourceReads = listOf(read)),
    )
}
