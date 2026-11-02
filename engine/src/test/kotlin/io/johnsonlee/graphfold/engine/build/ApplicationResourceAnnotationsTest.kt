package io.johnsonlee.graphfold.engine.build

import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypeReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationResourceAnnotationsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(AnnotationSite::class)
    fun `application metadata at every binding site retains unknown resource consumers`(site: AnnotationSite) {
        listOf("Lexternal/Value;", "Lkotlin/Unmodeled;").forEach { descriptor ->
            assertFalse(applicationResourceAnnotationsKnown(ClassReader(annotated(site, descriptor))), "$site: $descriptor")
        }
        assertTrue(applicationResourceAnnotationsKnown(ClassReader(annotated(site, ORIGIN_MARKER))), site.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["Lkotlin/Metadata;", "Lorg/jetbrains/annotations/NotNull;", "Lorg/jetbrains/annotations/Nullable;",
        "Lkotlin/jvm/internal/SourceDebugExtension;", "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/JvmStatic;", "Lkotlin/jvm/JvmField;", "Lkotlin/jvm/JvmName;"])
    fun `exact compiler metadata descriptors stay eligible for resource proof`(descriptor: String) {
        AnnotationSite.entries.forEach { site ->
            assertTrue(applicationResourceAnnotationsKnown(ClassReader(annotated(site, descriptor))), "$site: $descriptor")
        }
    }

    @Test
    fun `application field binding is unknown while an unused annotated library only requires graph analysis`() {
        val resources = Files.createDirectories(temporary.resolve("resources"))
        Files.writeString(resources.resolve("messages.properties"), "old=Hello\nnew=Bonjour")
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val library = Files.createDirectories(temporary.resolve("library"))
        val bytes = annotated(AnnotationSite.FIELD, "Lexternal/Value;")
        Files.write(library.resolve("Bound.class"), bytes)
        val external = captureProductionInventory(classes, listOf(resources), listOf(library), emptyList())
        assertTrue(external.resourceConsumersKnown)
        assertEquals(listOf(library), external.resourceDependencies)
        Files.write(classes.resolve("Bound.class"), bytes)
        val application = captureProductionInventory(classes, listOf(resources), emptyList(), emptyList())
        assertFalse(application.resourceConsumersKnown)
        Files.write(classes.resolve("Bound.class"), annotated(AnnotationSite.FIELD, ORIGIN_MARKER))
        assertTrue(captureProductionInventory(classes, listOf(resources), emptyList(), emptyList()).resourceConsumersKnown)
    }

    private fun annotated(site: AnnotationSite, descriptor: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_RECORD, "Bound", null, "java/lang/Record", null)
        when (site) {
            AnnotationSite.CLASS -> writer.visitAnnotation(descriptor, true).visitEnd()
            AnnotationSite.CLASS_TYPE -> writer.visitTypeAnnotation(TypeReference.newSuperTypeReference(-1).value,
                null, descriptor, false).visitEnd()
            AnnotationSite.FIELD, AnnotationSite.FIELD_TYPE -> writer.visitField(Opcodes.ACC_PRIVATE, "value",
                "Ljava/lang/String;", null, null).apply {
                if (site == AnnotationSite.FIELD) visitAnnotation(descriptor, false).visitEnd()
                else visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value, null, descriptor, true).visitEnd()
                visitEnd()
            }
            AnnotationSite.RECORD, AnnotationSite.RECORD_TYPE -> writer.visitRecordComponent("value", "Ljava/lang/String;", null).apply {
                if (site == AnnotationSite.RECORD) visitAnnotation(descriptor, true).visitEnd()
                else visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value, null, descriptor, false).visitEnd()
                visitEnd()
            }
            else -> methodAnnotation(writer, site, descriptor)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun methodAnnotation(writer: ClassWriter, site: AnnotationSite, descriptor: String) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value",
            "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            when (site) {
                AnnotationSite.METHOD -> visitAnnotation(descriptor, true).visitEnd()
                AnnotationSite.METHOD_TYPE -> visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value,
                    null, descriptor, false).visitEnd()
                AnnotationSite.PARAMETER -> visitParameterAnnotation(0, descriptor, true).visitEnd()
                AnnotationSite.INVISIBLE_PARAMETER -> visitParameterAnnotation(0, descriptor, false).visitEnd()
                else -> error("Not a method annotation site: $site")
            }
            visitEnd()
        }
    }

    enum class AnnotationSite {
        CLASS, CLASS_TYPE, FIELD, FIELD_TYPE, METHOD, METHOD_TYPE, PARAMETER, INVISIBLE_PARAMETER, RECORD, RECORD_TYPE,
    }
}
