package io.johnsonlee.graphfold.engine.bytecode.asm

/** Byte identity for narrow contracts tied to the engine's own runtime, without analyzed-artifact fallback. */
internal object EngineRuntimeClasses {
    fun bytes(owner: String): ByteArray? = Unit::class.java.getResourceAsStream("/$owner.class")?.use { it.readBytes() }

    fun requireBytes(owner: String): ByteArray = requireNotNull(bytes(owner)) { "Engine runtime class resource is missing: $owner" }

    fun matches(owner: String, definitions: List<ByteArray>): Boolean {
        val reference = bytes(owner) ?: return false
        return definitions.size == 1 && definitions.single().contentEquals(reference)
    }
}
