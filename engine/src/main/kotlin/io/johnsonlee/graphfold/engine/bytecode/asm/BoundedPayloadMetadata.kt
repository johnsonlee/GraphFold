package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/** Rejects metadata that can expose additional behavior or types to an opaque payload consumer. */
internal object BoundedPayloadMetadata {
    fun owner(owner: ClassNode): Boolean = owner.signature == null && empty(
        owner.visibleAnnotations?.filterNot { it.desc == ORIGIN_MARKER },
        owner.invisibleAnnotations?.filterNot { it.desc == ORIGIN_MARKER },
        owner.visibleTypeAnnotations, owner.invisibleTypeAnnotations, owner.attrs,
    )

    fun field(field: FieldNode): Boolean = field.signature == null && empty(
        field.visibleAnnotations, field.invisibleAnnotations,
        field.visibleTypeAnnotations, field.invisibleTypeAnnotations, field.attrs,
    )

    fun method(method: MethodNode): Boolean {
        val declaration = method.signature == null && method.annotationDefault == null && empty(
            method.exceptions, method.visibleAnnotations, method.invisibleAnnotations,
            method.visibleTypeAnnotations, method.invisibleTypeAnnotations, method.attrs,
        )
        val parameters = method.visibleParameterAnnotations.orEmpty().all { it.isNullOrEmpty() } &&
            method.invisibleParameterAnnotations.orEmpty().all { it.isNullOrEmpty() }
        val body = empty(method.visibleLocalVariableAnnotations, method.invisibleLocalVariableAnnotations) &&
            method.tryCatchBlocks.all { it.type == null && empty(it.visibleTypeAnnotations, it.invisibleTypeAnnotations) } &&
            method.instructions.all { empty(it.visibleTypeAnnotations, it.invisibleTypeAnnotations) }
        return declaration && parameters && body
    }

    private fun empty(vararg lists: List<*>?): Boolean = lists.all { it.isNullOrEmpty() }
}
