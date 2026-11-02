package io.johnsonlee.graphfold.engine.graphite

import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.objectweb.asm.tree.AnnotationNode

/**
 * Preserve Kotlin's externally usable contract while removing private declaration name-table churn.
 * This is valid only under the engine's reflection-free execution model; it is not a reflection ABI proof.
 * Unknown versions and malformed metadata are never stripped: null asks the caller to retain raw metadata
 * and report an analysis gap. All unrecognized annotation fields are preserved verbatim.
 */
internal object KotlinMetadataShape {
    fun normalize(annotation: AnnotationNode): AnnotationNode? {
        if (annotation.desc != METADATA_DESCRIPTOR) return annotation
        return try {
            normalizeMetadata(annotation)
        } catch (_: IllegalArgumentException) {
            null // Caller must retain the raw annotation and emit a blocking metadata diagnostic.
        } catch (_: IllegalStateException) {
            null // Strict writer may reject metadata decoded from an unsupported version.
        }
    }

    private fun normalizeMetadata(annotation: AnnotationNode): AnnotationNode {
        val values = annotation.values.orEmpty().chunked(2).associate { pair ->
            require(pair.size == 2) { "Malformed metadata annotation field" }
            requireNotNull(pair[0] as? String) { "Invalid metadata field name" } to pair[1]
        }
        val metadata = readMetadata(values)
        checkVersion(metadata.metadataVersion)
        val decoded = KotlinClassMetadata.readStrict(metadata)
        val container = when (decoded) {
            is KotlinClassMetadata.Class -> decoded.kmClass
            is KotlinClassMetadata.FileFacade -> decoded.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> decoded.kmPackage
            else -> return annotation // Lambda and multifile facade metadata are retained in full.
        }
        removePrivateDeclarations(container)
        val encoded = decoded.write()
        return AnnotationNode(annotation.desc).also { normalized ->
            normalized.values = annotation.values.orEmpty().toMutableList()
            replace(normalized, "d1", encoded.data1.toList())
            replace(normalized, "d2", encoded.data2.toList())
        }
    }

    private fun readMetadata(values: Map<String, Any?>): Metadata = Metadata(
        kind = values["k"] as? Int ?: 1,
        metadataVersion = ints(values["mv"]),
        data1 = strings(values["d1"]), data2 = strings(values["d2"]),
        extraString = (values["xs"] as? String).orEmpty(),
        packageName = (values["pn"] as? String).orEmpty(),
        extraInt = values["xi"] as? Int ?: 0,
    )

    private fun checkVersion(version: IntArray) {
        require(version.isNotEmpty() && version[0] <= MAX_MAJOR &&
            (version[0] < MAX_MAJOR || version.getOrElse(1) { 0 } <= MAX_MINOR)) {
            "Metadata version exceeds the schema verified by GraphFold"
        }
    }

    private fun removePrivateDeclarations(container: KmDeclarationContainer) {
        container.functions.removeAll { isPrivate(it.visibility) }
        container.properties.removeAll { isPrivate(it.visibility) }
    }

    private fun isPrivate(visibility: Visibility): Boolean =
        visibility == Visibility.PRIVATE || visibility == Visibility.PRIVATE_TO_THIS

    private fun replace(annotation: AnnotationNode, key: String, value: List<String>) {
        val index = annotation.values.indices.step(2).firstOrNull { annotation.values[it] == key }
        if (index == null) annotation.values.addAll(listOf(key, value)) else annotation.values[index + 1] = value
    }

    private fun ints(value: Any?): IntArray = when (value) {
        null -> intArrayOf()
        is IntArray -> value
        is List<*> -> value.map { requireNotNull(it as? Int) { "Invalid metadata integer array" } }.toIntArray()
        else -> throw IllegalArgumentException("Invalid metadata integer array")
    }

    private fun strings(value: Any?): Array<String> = when (value) {
        null -> emptyArray()
        is Array<*> -> value.map { requireNotNull(it as? String) { "Invalid metadata string array" } }.toTypedArray()
        is List<*> -> value.map { requireNotNull(it as? String) { "Invalid metadata string array" } }.toTypedArray()
        else -> throw IllegalArgumentException("Invalid metadata string array")
    }

    private const val MAX_MAJOR = 2
    private const val MAX_MINOR = 1
    private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
}
