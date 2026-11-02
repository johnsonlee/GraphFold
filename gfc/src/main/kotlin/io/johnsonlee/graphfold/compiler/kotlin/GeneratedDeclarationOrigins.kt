package io.johnsonlee.graphfold.compiler.kotlin

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.tree.ClassNode

/**
 * Classifies library inline implementation classes without inventing anonymous source identities.
 * Kotlin's synthetic metadata is a compiler category; the actual EnclosingMethod must resolve to a
 * method emitted in this compilation whose owner has verified physical source provenance. These
 * owners keep their original JVM names, so this does not authorize renumbering or body-hash pairing.
 */
internal fun generatedDeclarationOwners(classFiles: List<ByteArray>, verifiedEnclosingOwners: Set<String>): Set<String> {
    val classes = classFiles.map { bytes ->
        ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) }
    }.groupBy { it.name }
    return classes.values.filter { it.size == 1 }.map { it.single() }.filter { node ->
        val metadata = node.visibleAnnotations.orEmpty().filter { it.desc == "Lkotlin/Metadata;" }
        val synthetic = metadata.singleOrNull()?.values.orEmpty().chunked(2)
            .filter { it.first() == "k" }.singleOrNull()?.last() == SYNTHETIC_METADATA_KIND
        val enclosing = classes[node.outerClass]?.singleOrNull()
        synthetic && node.outerClass in verifiedEnclosingOwners && enclosing != null &&
            enclosing.methods.count { it.name == node.outerMethod && it.desc == node.outerMethodDesc } == 1
    }.mapTo(mutableSetOf()) { it.name }
}

private const val SYNTHETIC_METADATA_KIND = 3
