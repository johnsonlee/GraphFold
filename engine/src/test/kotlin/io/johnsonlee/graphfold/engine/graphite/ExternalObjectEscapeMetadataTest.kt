package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.Attribute
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypeReference
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableAnnotationNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeAnnotationNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalObjectEscapeMetadataTest {
    @Test
    fun `ordinary DTO with debug provenance and untyped finally handler remains bounded`() {
        val dto = payload()
        dto.visibleAnnotations = mutableListOf(AnnotationNode(ORIGIN_MARKER))
        dto.invisibleAnnotations = mutableListOf(AnnotationNode(ORIGIN_MARKER))
        val method = dto.methods.single()
        method.desc = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        method.maxLocals = Type.getArgumentTypes(method.desc).size + 1
        method.visibleParameterAnnotations = arrayOf(null, mutableListOf())
        method.invisibleParameterAnnotations = arrayOf(mutableListOf(), null)
        addHandler(method, null)
        val gap = escape(dto)
        assertFalse(gap.globalObservation)
        assertEquals(setOf("sample.Payload"), gap.observedOwners)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataCases")
    fun `payload metadata with unmodeled type or behavior references prevents bounded observation`(
        description: String, mutate: (ClassNode) -> Unit,
    ) {
        val dto = payload().also(mutate)
        val gap = escape(dto)
        assertTrue(gap.globalObservation, description)
        assertTrue(gap.observedOwners.isEmpty(), description)
    }

    @Test
    fun `multidimensional array allocation cannot hide its element type in a scalar returning method`() {
        val dto = payload()
        val method = dto.methods.single()
        method.instructions.clear()
        method.desc = "()I"
        method.visitInsn(Opcodes.ICONST_1)
        method.visitInsn(Opcodes.ICONST_1)
        method.instructions.add(MultiANewArrayInsnNode("[[Lhidden/Element;", 2))
        method.visitInsn(Opcodes.ARRAYLENGTH)
        method.visitInsn(Opcodes.IRETURN)
        val gap = escape(dto)
        assertTrue(gap.globalObservation)
        assertTrue(gap.observedOwners.isEmpty())
    }

    private fun escape(dto: ClassNode): MethodAnalysisGap {
        val caller = ClassNode().also { it.name = "sample/Entry"; it.superName = "java/lang/Object" }
        caller.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "expose", "(Lsample/Payload;)V", null, null).also {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESTATIC, "external/Encoder", "encode", "(Ljava/lang/Object;)V", false)
            it.visitInsn(Opcodes.RETURN)
            it.visitMaxs(1, 1)
        })
        return ExternalObjectEscape(mapOf(dto.name to dto, caller.name to caller))
            .methodDiagnostics().getValue("sample.Entry.expose(Lsample/Payload;)V").single()
    }

    private fun payload(): ClassNode = ClassNode().also { dto ->
        dto.name = "sample/Payload"
        dto.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL
        dto.superName = "java/lang/Object"
        dto.fields.add(FieldNode(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null))
        dto.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).also {
            it.instructions.add(LabelNode())
            it.visitTypeInsn(Opcodes.NEW, "java/lang/String")
            it.visitInsn(Opcodes.DUP)
            it.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "()V", false)
            it.visitVarInsn(Opcodes.ASTORE, 1)
            it.visitVarInsn(Opcodes.ALOAD, 1)
            it.visitInsn(Opcodes.ARETURN)
            it.instructions.add(LabelNode())
            it.visitMaxs(2, 2)
        })
    }

    companion object {
        @JvmStatic
        fun metadataCases(): List<Arguments> {
            val cases = linkedMapOf<String, (ClassNode) -> Unit>(
                "class generic bound" to { it.signature = "<T:Lhidden/Handler;>Ljava/lang/Object;" },
                "field generic signature" to { it.fields.single().signature = "Ljava/lang/String;" },
                "method generic bound" to { it.methods.single().signature = "<T:Lhidden/Handler;>()Ljava/lang/String;" },
                "declared exception" to { it.methods.single().exceptions.add("hidden/Failure") },
                "typed exception handler" to { addHandler(it.methods.single(), "hidden/Failure") },
                "class custom attribute" to { it.attrs = mutableListOf(opaqueAttribute()) },
                "field custom attribute" to { it.fields.single().attrs = mutableListOf(opaqueAttribute()) },
                "method custom attribute" to { it.methods.single().attrs = mutableListOf(opaqueAttribute()) },
                "annotation default class reference" to { it.methods.single().annotationDefault = Type.getObjectType("hidden/Handler") },
            )
            for (visible in listOf(true, false)) {
                cases["class annotation visible=$visible"] = { it.visitAnnotation("Lhidden/Using;", visible).visit("value", target()) }
                cases["field annotation visible=$visible"] = {
                    it.fields.single().visitAnnotation("Lhidden/Using;", visible).visit("value", target())
                }
                cases["method annotation visible=$visible"] = {
                    it.methods.single().visitAnnotation("Lhidden/Using;", visible).visit("value", target())
                }
                cases["class type annotation visible=$visible"] = {
                    it.visitTypeAnnotation(TypeReference.newSuperTypeReference(-1).value, null, "Lhidden/Using;", visible)
                        .visit("value", target())
                }
                cases["field type annotation visible=$visible"] = {
                    it.fields.single().visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value,
                        null, "Lhidden/Using;", visible).visit("value", target())
                }
                cases["method type annotation visible=$visible"] = {
                    it.methods.single().visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value,
                        null, "Lhidden/Using;", visible).visit("value", target())
                }
                cases["parameter annotation visible=$visible"] = {
                    it.methods.single().desc = "(Ljava/lang/String;)Ljava/lang/String;"
                    it.methods.single().visitParameterAnnotation(0, "Lhidden/Using;", visible).visit("value", target())
                }
                cases["instruction type annotation visible=$visible"] = {
                    val instruction = it.methods.single().instructions[1]
                    val annotations = mutableListOf(typeAnnotation(TypeReference.NEW))
                    if (visible) instruction.visibleTypeAnnotations = annotations else instruction.invisibleTypeAnnotations = annotations
                }
                cases["local type annotation visible=$visible"] = { localAnnotation(it.methods.single(), visible) }
                cases["handler type annotation visible=$visible"] = {
                    val handler = addHandler(it.methods.single(), null)
                    val annotations = mutableListOf(TypeAnnotationNode(TypeReference.newTryCatchReference(0).value, null, "Lhidden/Using;"))
                    if (visible) handler.visibleTypeAnnotations = annotations else handler.invisibleTypeAnnotations = annotations
                }
            }
            return cases.map { (name, mutation) -> Arguments.of(name, mutation) }
        }

        private fun target(): Type = Type.getObjectType("hidden/Handler")

        private fun typeAnnotation(sort: Int): TypeAnnotationNode =
            TypeAnnotationNode(TypeReference.newTypeReference(sort).value, null, "Lhidden/Using;")

        private fun localAnnotation(method: MethodNode, visible: Boolean) {
            val annotations = mutableListOf(LocalVariableAnnotationNode(
                TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).value, null,
                arrayOf(method.instructions.first as LabelNode), arrayOf(method.instructions.last as LabelNode),
                intArrayOf(1), "Lhidden/Using;",
            ))
            if (visible) method.visibleLocalVariableAnnotations = annotations else method.invisibleLocalVariableAnnotations = annotations
        }

        private fun addHandler(method: MethodNode, type: String?): TryCatchBlockNode {
            val start = method.instructions.first as LabelNode
            val end = method.instructions.last as LabelNode
            val handler = LabelNode()
            method.instructions.add(handler)
            method.visitInsn(Opcodes.POP)
            method.visitLdcInsn("failed")
            method.visitInsn(Opcodes.ARETURN)
            return TryCatchBlockNode(start, end, handler, type).also(method.tryCatchBlocks::add)
        }

        private fun opaqueAttribute(): Attribute = object : Attribute("OpaqueBehavior") {}
    }
}
