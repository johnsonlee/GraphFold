package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.Type
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Proven immutable contents, independent of any application class name.
 * Void cannot occur as a JVM stack/local value, so this representation cannot collide
 * with a real reference or ASM's null-typed uninitialized value. Reference behavior is
 * explicit so normal branch merges retain the immutable proof.
 */
internal object ImmutableGraphValue : BasicValue(Type.VOID_TYPE) {
    override fun isReference(): Boolean = true

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
