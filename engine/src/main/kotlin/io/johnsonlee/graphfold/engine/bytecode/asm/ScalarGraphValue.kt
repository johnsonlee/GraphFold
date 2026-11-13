package io.johnsonlee.graphfold.engine.bytecode.asm

import org.objectweb.asm.Type
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Proven scalar-only contents, independent of any application class name.
 * Arrays may remain mutable; this value proves absence of application-object payloads, not absence of effects.
 * Void cannot occur as a JVM stack/local value, so this representation cannot collide
 * with a real reference or ASM's null-typed uninitialized value. Reference behavior is
 * explicit so normal branch merges retain the payload proof.
 */
internal object ScalarGraphValue : BasicValue(Type.VOID_TYPE) {
    override fun isReference(): Boolean = true

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
