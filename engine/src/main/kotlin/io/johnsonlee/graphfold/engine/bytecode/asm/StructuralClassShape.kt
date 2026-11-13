package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.snapshot.StructuralClassShape
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InnerClassNode

/** Split only explicit ownership records; all other class metadata is retained literally. */
internal fun structuralClassShape(
    node: ClassNode, declaration: List<Any?>, owner: String, diagnostics: MutableList<String>, stable: (Any?) -> String,
): StructuralClassShape {
    val metadataNesting = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
        .withIndex().flatMap { (index, annotation) -> KotlinMetadataShape.nestedClasses(annotation).map { index to it } }
    val nestedNames = metadataNesting.map { it.second }.toSet()
    val core = declaration.dropLast(1).toMutableList().also {
        it[NEST_MEMBERS_INDEX] = emptyList<String>()
        it[INNER_CLASSES_INDEX] = emptyList<String>()
    }
    val nesting = node.nestMembers.orEmpty().map { it.replace('/', '.') to "nest:$it" } +
        node.innerClasses.filterNot(::implicitLookupEntry).map {
            it.name.replace('/', '.') to stable(listOf(it.name, it.outerName, it.innerName, it.access))
        } + metadataNesting.map { (index, nested) -> "$owner\$$nested" to "kotlin:$index:$nested" }
    return StructuralClassShape(stable(DeclarationMetadata.classShape(core, owner, diagnostics, nestedNames)), nesting)
}

internal fun implicitLookupEntry(entry: InnerClassNode): Boolean =
    entry.name == "java/lang/invoke/MethodHandles\$Lookup" && entry.outerName == "java/lang/invoke/MethodHandles" &&
        entry.innerName == "Lookup" && entry.access == (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)

private const val NEST_MEMBERS_INDEX = 6
private const val INNER_CLASSES_INDEX = 10
