package io.johnsonlee.graphfold.engine.origin

import kotlin.contracts.ExperimentalContracts
import kotlin.metadata.KmContract
import kotlin.metadata.KmEffect
import kotlin.metadata.KmEffectType
import kotlin.metadata.KmFlexibleTypeUpperBound
import kotlin.metadata.KmPropertyAccessorAttributes
import kotlin.metadata.isValue
import kotlin.metadata.Modality
import kotlin.metadata.modality
import kotlin.metadata.isNullable
import kotlin.metadata.isVar
import kotlin.metadata.isDelegated
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.jvm.syntheticMethodForDelegate
import kotlin.metadata.jvm.anonymousObjectOriginName
import kotlin.metadata.jvm.lambdaClassOriginName
import kotlin.metadata.ExperimentalContextReceivers
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.jvm.JvmFieldSignature
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalContextReceivers::class, ExperimentalContracts::class)
class OriginKotlinMetadataTest {
    @Test fun `generic context receiver constructor and property JVM identities remap structurally`() {
        val declaration = KmClass().apply {
            name = ".sample/Host\$1"
            supertypes.add(type("kotlin/Any"))
            typeParameters.add(KmTypeParameter("T", 0, KmVariance.INVARIANT).apply { upperBounds.add(type()) })
            constructors.add(KmConstructor().apply {
                valueParameters.add(parameter("parent"))
                signature = JvmMethodSignature("<init>", "(Lsample/Host\$1;)V")
            })
            functions.add(KmFunction("read").apply {
                returnType = type()
                receiverParameterType = type()
                contextReceiverTypes.add(type())
                valueParameters.add(parameter("values").apply {
                    type = type("kotlin/Array").apply { arguments.add(KmTypeProjection(KmVariance.INVARIANT, type())) }
                    varargElementType = type()
                })
                signature = JvmMethodSignature("read", "(Lsample/Host\$1;Lsample/Host\$1;[Lsample/Host\$1;)Lsample/Host\$1;")
            })
            properties.add(KmProperty("value").apply {
                returnType = type()
                fieldSignature = JvmFieldSignature("value", "Lsample/Host\$1;")
                getterSignature = JvmMethodSignature("getValue", "()Lsample/Host\$1;")
            })
        }
        val annotation = annotation(declaration)
        remap(annotation)
        val result = decode(annotation)
        assertEquals(".sample/Stable", result.name)
        assertEquals(KmClassifier.Class("sample/Stable"), result.typeParameters.single().upperBounds.single().classifier)
        assertEquals("(Lsample/Stable;)V", result.constructors.single().signature?.descriptor)
        val function = result.functions.single()
        assertEquals("(Lsample/Stable;Lsample/Stable;[Lsample/Stable;)Lsample/Stable;", function.signature?.descriptor)
        assertEquals(KmClassifier.Class("sample/Stable"), function.contextReceiverTypes.single().classifier)
        assertEquals(KmClassifier.Class("sample/Stable"), function.valueParameters.single().varargElementType?.classifier)
        val property = result.properties.single()
        assertEquals("Lsample/Stable;", property.fieldSignature?.descriptor)
        assertEquals("()Lsample/Stable;", property.getterSignature?.descriptor)
        assertEquals(KmClassifier.Class("sample/Stable"), property.returnType.classifier)
    }

    @Test fun `unsupported type aliases retain metadata and report an explicit boundary`() {
        val declaration = base().apply {
            typeAliases.add(KmTypeAlias("Alias").apply {
                underlyingType = type()
                expandedType = type()
            })
        }
        val annotation = annotation(declaration)
        val before = annotation.values.toList()
        assertTrue(assertFailsWith<IllegalArgumentException> { remap(annotation) }.message.orEmpty().contains("Type aliases"))
        assertEquals(before, annotation.values)
    }

    @Test fun `annotated type bounds cannot silently discard annotation semantics`() {
        val declaration = base().apply {
            typeParameters.add(KmTypeParameter("T", 0, KmVariance.INVARIANT).apply {
                upperBounds.add(type().apply { annotations.add(KmAnnotation("sample/Qualifier", emptyMap())) })
            })
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { remap(annotation(declaration)) }
            .message.orEmpty().contains("Type annotations"))
    }

    @Test fun `extension property setters and compiler synthetic signatures retain typed owners`() {
        val declaration = base().apply {
            anonymousObjectOriginName = "sample/Host\$1"
            properties.add(KmProperty("value").apply {
                isVar = true
                isDelegated = true
                returnType = type()
                receiverParameterType = type()
                contextReceiverTypes.add(type())
                setter = KmPropertyAccessorAttributes()
                setterParameter = parameter("value")
                getterSignature = JvmMethodSignature("getValue", "(Lsample/Host\$1;Lsample/Host\$1;)Lsample/Host\$1;")
                setterSignature = JvmMethodSignature("setValue", "(Lsample/Host\$1;Lsample/Host\$1;Lsample/Host\$1;)V")
                syntheticMethodForAnnotations = JvmMethodSignature("getValue\$annotations", "(Lsample/Host\$1;)V")
                syntheticMethodForDelegate = JvmMethodSignature("getValue\$delegate", "(Lsample/Host\$1;)Ljava/lang/Object;")
            })
        }
        val annotation = annotation(declaration)
        remap(annotation)
        val rewritten = decode(annotation)
        assertEquals("sample/Stable", rewritten.anonymousObjectOriginName)
        val property = rewritten.properties.single()
        assertEquals(KmClassifier.Class("sample/Stable"), property.setterParameter?.type?.classifier)
        assertEquals(KmClassifier.Class("sample/Stable"), property.contextReceiverTypes.single().classifier)
        assertEquals("(Lsample/Stable;Lsample/Stable;Lsample/Stable;)V", property.setterSignature?.descriptor)
        assertEquals("(Lsample/Stable;)V", property.syntheticMethodForAnnotations?.descriptor)
        assertEquals("(Lsample/Stable;)Ljava/lang/Object;", property.syntheticMethodForDelegate?.descriptor)
    }

    @Test fun `platform bounds outer types star projections and generic classifiers survive round trip`() {
        val declaration = base().apply {
            functions.add(KmFunction("platform").apply {
                returnType = type("kotlin/collections/List").apply {
                    arguments.add(KmTypeProjection.STAR)
                    flexibleTypeUpperBound = KmFlexibleTypeUpperBound(type("kotlin/collections/List").apply {
                        isNullable = true
                        arguments.add(KmTypeProjection.STAR)
                    }, "kotlin.jvm.PlatformType")
                }
                lambdaClassOriginName = "sample/Host\$1"
            })
            functions.add(KmFunction("nested").apply {
                returnType = type("sample/Outer.Inner").apply { outerType = type("sample/Outer") }
            })
            functions.add(KmFunction("generic").apply {
                typeParameters.add(KmTypeParameter("T", 0, KmVariance.INVARIANT).apply { upperBounds.add(type()) })
                returnType = KmType().apply { classifier = KmClassifier.TypeParameter(0) }
            })
        }
        val annotation = annotation(declaration)
        remap(annotation)
        val functions = decode(annotation).functions.associateBy { it.name }
        assertEquals("sample/Stable", functions.getValue("platform").lambdaClassOriginName)
        assertEquals(null, functions.getValue("platform").returnType.arguments.single().type)
        assertTrue(functions.getValue("platform").returnType.flexibleTypeUpperBound?.type?.isNullable == true)
        assertEquals(KmClassifier.Class("sample/Outer"), functions.getValue("nested").returnType.outerType?.classifier)
        assertEquals(KmClassifier.TypeParameter(0), functions.getValue("generic").returnType.classifier)
    }

    @Test fun `contracts and annotated type parameters remain explicit unsupported boundaries`() {
        val contracted = base().apply {
            functions.add(KmFunction("check").apply {
                returnType = type("kotlin/Boolean")
                contract = KmContract().apply { effects.add(KmEffect(KmEffectType.RETURNS_NOT_NULL, null)) }
            })
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { remap(annotation(contracted)) }
            .message.orEmpty().contains("Contracts"))
        val annotated = base().apply {
            typeParameters.add(KmTypeParameter("T", 0, KmVariance.INVARIANT).apply {
                annotations.add(KmAnnotation("sample/Qualifier", emptyMap()))
            })
        }
        assertTrue(assertFailsWith<IllegalArgumentException> { remap(annotation(annotated)) }
            .message.orEmpty().contains("Type parameter annotations"))
    }

    @Test fun `sealed subclass lists preserve unmapped identities while remapping a moved subclass`() {
        val declaration = KmClass().apply {
            name = "sample/Parent"
            modality = Modality.SEALED
            supertypes.add(type("kotlin/Any"))
            sealedSubclasses.addAll(listOf("sample/MovedChild", "sample/UnchangedChild"))
        }
        val owners = mapOf("sample.MovedChild" to "sample.StableChild")
        val annotation = annotation(declaration)
        remapKotlinOriginMetadata(annotation, OriginRemapper(owners), owners)
        assertEquals(listOf("sample/StableChild", "sample/UnchangedChild"), decode(annotation).sealedSubclasses)
    }

    @Test fun `value class representation and contextual class receiver preserve mapped JVM types`() {
        val owners = mapOf("sample.Previous" to "sample.Current")
        val valueClass = KmClass().apply {
            name = "sample/Box"
            isValue = true
            supertypes.add(type("kotlin/Any"))
            inlineClassUnderlyingPropertyName = "value"
            inlineClassUnderlyingType = type("sample/Previous")
            properties.add(KmProperty("value").apply { returnType = type("sample/Previous") })
            constructors.add(KmConstructor().apply {
                valueParameters.add(KmValueParameter("value").apply { type = type("sample/Previous") })
            })
        }
        val encoded = annotation(valueClass)
        remapKotlinOriginMetadata(encoded, OriginRemapper(owners), owners)
        val rewritten = decode(encoded)
        assertEquals(KmClassifier.Class("sample/Current"), rewritten.inlineClassUnderlyingType?.classifier)
        assertEquals("value", rewritten.inlineClassUnderlyingPropertyName)
        val contextual = KmClass().apply {
            name = "sample/Contextual"
            supertypes.add(type("kotlin/Any"))
            contextReceiverTypes.add(type("sample/Previous"))
        }
        val context = annotation(contextual)
        remapKotlinOriginMetadata(context, OriginRemapper(owners), owners)
        assertEquals(KmClassifier.Class("sample/Current"), decode(context).contextReceiverTypes.single().classifier)
    }

    @Test fun `external type alias abbreviation survives remapping its expanded class`() {
        val declaration = base().apply {
            functions.add(KmFunction("alias").apply {
                returnType = type().apply {
                    abbreviatedType = KmType().apply { classifier = KmClassifier.TypeAlias("library/Alias") }
                }
            })
        }
        val annotation = annotation(declaration)
        remap(annotation)
        val result = decode(annotation).functions.single().returnType
        assertEquals(KmClassifier.Class("sample/Stable"), result.classifier)
        assertEquals(KmClassifier.TypeAlias("library/Alias"), result.abbreviatedType?.classifier)
    }

    private fun base() = KmClass().apply { name = ".sample/Host\$1"; supertypes.add(type("kotlin/Any")) }
    private fun type(name: String = "sample/Host\$1") = KmType().apply { classifier = KmClassifier.Class(name) }
    private fun parameter(name: String) = KmValueParameter(name).apply { type = type() }
    private fun annotation(declaration: KmClass): AnnotationNode {
        val metadata = KotlinClassMetadata.Class(declaration, JvmMetadataVersion(2, 1, 0), 0).write()
        return AnnotationNode("Lkotlin/Metadata;").also {
            it.values = mutableListOf("k", 1, "mv", metadata.metadataVersion.toList(),
                "d1", metadata.data1.toList(), "d2", metadata.data2.toList(), "xi", metadata.extraInt)
        }
    }
    private fun remap(annotation: AnnotationNode) = remapKotlinOriginMetadata(annotation,
        OriginRemapper(OWNERS), OWNERS)

    @Suppress("UNCHECKED_CAST")
    private fun decode(annotation: AnnotationNode): KmClass {
        val values = annotation.values.chunked(2).associate { it[0] as String to it[1] }
        val metadata = Metadata(kind = 1, metadataVersion = intArrayOf(2, 1, 0),
            data1 = (values.getValue("d1") as List<String>).toTypedArray(),
            data2 = (values.getValue("d2") as List<String>).toTypedArray())
        return (KotlinClassMetadata.readStrict(metadata) as KotlinClassMetadata.Class).kmClass
    }
    private companion object { val OWNERS = mapOf("sample.Host\$1" to "sample.Stable") }
}
