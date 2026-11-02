package io.johnsonlee.graphfold.engine.origin

import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmLambda
import kotlin.metadata.KmPackage
import kotlin.metadata.KmType
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OriginMetadataKindsTest {
    @Test fun `file and multifile implementation metadata preserve facade identity and remap return types`() {
        val factories = listOf<(KmPackage) -> KotlinClassMetadata>(
            { KotlinClassMetadata.FileFacade(it, VERSION, 0) },
            { KotlinClassMetadata.MultiFileClassPart(it, "sample/Facade", VERSION, 0) },
        )
        factories.forEach { factory ->
            val original = factory(KmPackage().apply { functions.add(function()) })
            val rewritten = roundTrip(original.write())
            val container = when (rewritten) {
                is KotlinClassMetadata.FileFacade -> rewritten.kmPackage
                is KotlinClassMetadata.MultiFileClassPart -> {
                    assertEquals("sample/Facade", rewritten.facadeClassName)
                    rewritten.kmPackage
                }
                else -> error("Unexpected kind")
            }
            assertEquals(KmClassifier.Class("sample/Stable"), container.functions.single().returnType.classifier)
        }
    }

    @Test fun `facade parts and synthetic lambda metadata remain correctly typed`() {
        val facade = KotlinClassMetadata.MultiFileClassFacade(listOf("sample/Facade__OneKt", "sample/Facade__TwoKt"), VERSION, 0)
        assertEquals(facade.partClassNames, (roundTrip(facade.write()) as KotlinClassMetadata.MultiFileClassFacade).partClassNames)
        val lambda = KmLambda().apply { function = function() }
        val result = roundTrip(KotlinClassMetadata.SyntheticClass(lambda, VERSION, 0).write()) as KotlinClassMetadata.SyntheticClass
        assertEquals(KmClassifier.Class("sample/Stable"), result.kmLambda?.function?.returnType?.classifier)
        val helper = roundTrip(KotlinClassMetadata.SyntheticClass(null, VERSION, 0).write()) as KotlinClassMetadata.SyntheticClass
        assertEquals(null, helper.kmLambda)
    }

    @Test fun `identity normalization preserves even metadata outside the supported schema`() {
        val annotation = AnnotationNode("Lkotlin/Metadata;").apply { values = mutableListOf("mv", listOf(99)) }
        val original = annotation.values.toList()
        remapKotlinOriginMetadata(annotation, OriginRemapper(emptyMap()), emptyMap())
        remapKotlinOriginMetadata(annotation, OriginRemapper(mapOf("sample.Host" to "sample.Host")),
            mapOf("sample.Host" to "sample.Host"))
        assertEquals(original, annotation.values)
    }

    @Test fun `malformed metadata structure and unsupported versions never normalize silently`() {
        val invalid = listOf(
            mutableListOf<Any>("mv"), mutableListOf<Any>(1, listOf(2, 1)),
            mutableListOf<Any>("mv", listOf("two")), mutableListOf<Any>("mv", listOf(2, 2)),
            mutableListOf<Any>("mv", listOf(3)), mutableListOf<Any>("k", 1),
        )
        invalid.forEach { values ->
            val annotation = AnnotationNode("Lkotlin/Metadata;").also { it.values = values }
            assertFailsWith<IllegalArgumentException> { remap(annotation) }
            assertEquals(values, annotation.values)
        }
        val malformedStrings = AnnotationNode("Lkotlin/Metadata;").apply {
            values = mutableListOf("mv", intArrayOf(2, 1, 0), "d1", "not an array")
        }
        assertTrue(assertFailsWith<IllegalStateException> { remap(malformedStrings) }.message.orEmpty().contains("string array"))
    }

    @Test fun `minimal legacy synthetic metadata retains kind while omitted arrays are regenerated`() {
        val annotation = AnnotationNode("Lkotlin/Metadata;").apply {
            values = mutableListOf("k", 3, "mv", listOf(1, 9, 0))
        }
        remap(annotation)
        val values = annotation.values.chunked(2).associate { it[0] as String to it[1] }
        assertEquals(3, values["k"])
        assertEquals(emptyList<String>(), values["d1"])
        assertEquals(emptyList<String>(), values["d2"])
    }

    @Test fun `unknown kinds and invalid string array entries fail without metadata erasure`() {
        val unknown = AnnotationNode("Lkotlin/Metadata;").apply { values = mutableListOf("k", 99, "mv", listOf(2, 1, 0)) }
        assertTrue(assertFailsWith<IllegalStateException> { remap(unknown) }.message.orEmpty().contains("Unknown"))
        val missing = AnnotationNode("Lkotlin/Metadata;")
        assertFailsWith<IllegalArgumentException> { remap(missing) }
        listOf<Any>(arrayOf<Any>(1), listOf(1)).forEach { invalidArray ->
            val annotation = AnnotationNode("Lkotlin/Metadata;").apply {
                values = mutableListOf("k", 3, "mv", listOf(2, 1, 0), "d1", invalidArray)
            }
            assertFailsWith<IllegalArgumentException> { remap(annotation) }
        }
    }

    private fun function() = KmFunction("produce").apply {
        returnType = KmType().apply { classifier = KmClassifier.Class("sample/Host\$1") }
    }

    private fun roundTrip(metadata: Metadata): KotlinClassMetadata {
        val annotation = AnnotationNode("Lkotlin/Metadata;").apply {
            values = mutableListOf("k", metadata.kind, "mv", metadata.metadataVersion,
                "d1", metadata.data1, "d2", metadata.data2, "xs", metadata.extraString,
                "pn", metadata.packageName, "xi", metadata.extraInt)
        }
        remap(annotation)
        val values = annotation.values.chunked(2).associate { it[0] as String to it[1] }
        @Suppress("UNCHECKED_CAST")
        return KotlinClassMetadata.readStrict(Metadata(kind = metadata.kind, metadataVersion = metadata.metadataVersion,
            data1 = (values.getValue("d1") as List<String>).toTypedArray(),
            data2 = (values.getValue("d2") as List<String>).toTypedArray(),
            extraString = values["xs"] as String, packageName = metadata.packageName, extraInt = metadata.extraInt))
    }

    private fun remap(annotation: AnnotationNode) = remapKotlinOriginMetadata(annotation, OriginRemapper(OWNERS), OWNERS)
    private companion object {
        val VERSION = JvmMetadataVersion(2, 1, 0)
        val OWNERS = mapOf("sample.Host\$1" to "sample.Stable")
    }
}
