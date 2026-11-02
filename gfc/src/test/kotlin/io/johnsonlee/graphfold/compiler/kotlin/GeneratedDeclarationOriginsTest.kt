package io.johnsonlee.graphfold.compiler.kotlin

import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedDeclarationOriginsTest {
    @Test fun `synthetic compiler metadata and verified existing enclosing method establish category without names`() {
        val classes = listOf(owner(), generated())
        assertEquals(setOf("sample/Implementation"), generatedDeclarationOwners(classes, setOf("sample/Example")))
        assertTrue(generatedDeclarationOwners(classes, emptySet()).isEmpty())
        assertTrue(generatedDeclarationOwners(emptyList(), setOf("sample/Example")).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "ordinary", "duplicate", "missingKind", "duplicateKind", "other"])
    fun `only unique explicit synthetic metadata permits generated declaration classification`(metadata: String) {
        assertTrue(generatedDeclarationOwners(listOf(owner(), generated(metadata = metadata)), setOf("sample/Example")).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["owner", "method", "descriptor", "nullMethod", "nullOwner", "duplicateOwner", "duplicateClass",
        "duplicateMethod"])
    fun `unresolvable or ambiguous enclosing context cannot classify missing IR`(missing: String) {
        val generated = generated(
            outerOwner = if (missing == "nullOwner") null else "sample/Example",
            method = if (missing == "nullMethod") null else if (missing == "method") "absent" else "run",
            descriptor = if (missing == "descriptor") "(I)V" else "()V",
        )
        val classes = mutableListOf(generated)
        if (missing != "owner") classes += owner(duplicateMethod = missing == "duplicateMethod")
        if (missing == "duplicateOwner") classes += owner()
        if (missing == "duplicateClass") classes += generated
        assertTrue(generatedDeclarationOwners(classes, setOf("sample/Example")).isEmpty())
    }

    private fun owner(duplicateMethod: Boolean = false): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Example", null, "java/lang/Object", null)
        repeat(if (duplicateMethod) 2 else 1) { visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "run", "()V", null, null).visitEnd() }
        visitEnd()
    }.toByteArray()

    private fun generated(metadata: String = "synthetic", outerOwner: String? = "sample/Example", method: String? = "run",
        descriptor: String = "()V"): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Implementation", null, "java/lang/Object", null)
        if (outerOwner != null) visitOuterClass(outerOwner, method, descriptor)
        if (metadata != "absent") repeat(if (metadata == "duplicate") 2 else 1) {
            visitAnnotation(if (metadata == "other") "Lsample/Other;" else "Lkotlin/Metadata;", true).apply {
                visit("xi", 48)
                if (metadata != "missingKind") visit("k", if (metadata == "ordinary") 1 else 3)
                if (metadata == "duplicateKind") visit("k", 3)
                visitEnd()
            }
        }
        visitEnd()
    }.toByteArray()
}
