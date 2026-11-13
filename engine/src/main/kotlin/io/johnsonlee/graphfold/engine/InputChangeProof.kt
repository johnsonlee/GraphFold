package io.johnsonlee.graphfold.engine


import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.hasSameEntries
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead
import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

/** Inputs outside declared source ownership are assessed by production outputs, never a filename allowlist. */
internal class InputChangeProof(
    val changes: List<ChangedFile>,
    private val base: ContentInventory?,
    private val head: ContentInventory?,
    private val sourceChanges: Set<ChangedFile> = emptySet(),
) {
    val unchanged: Boolean get() = base.hasSameEntries(head)
    val requiresResourceAnalysis: Boolean get() = changes.isNotEmpty() && resourceContentChanged()

    private fun resourceContentChanged(): Boolean {
        if (base == null || head == null) return false
        return base.resourceEntries != head.resourceEntries || base.resourceIndependentEntries.any { (entry, marker) ->
            marker.startsWith("properties:") && base.nonClassEntries[entry] != head.nonClassEntries[entry]
        }
    }

    fun gap(resourcesProved: Boolean = false): Diagnostic? {
        if (base == null || head == null) return Diagnostic(
            AnalysisStage.BUILD, DiagnosticCode.PRODUCTION_INVENTORY_UNAVAILABLE,
            "The caller did not establish complete static resources, external dependencies and outputs " +
                "for both revisions; cannot determine the impact of changed build inputs",
        )
        val oldEntries = if (resourcesProved) base.resourceIndependentEntries else base.nonClassEntries
        val newEntries = if (resourcesProved) head.resourceIndependentEntries else head.nonClassEntries
        val byteDifferences = (oldEntries.keys + newEntries.keys).filter { oldEntries[it] != newEntries[it] }
        val placementDifferences = base.classPlacements.keys.intersect(head.classPlacements.keys).filter {
            base.classPlacements[it] != head.classPlacements[it]
        }.map { "classPlacement/$it" }
        val differences = (byteDifferences + placementDifferences).sorted()
        return if (differences.isEmpty()) null else Diagnostic(
            AnalysisStage.COMPARE, DiagnosticCode.PRODUCTION_OUTPUT_CHANGED,
            "Normalized resources or external dependencies differ outside the class-folding model: " +
                differences.take(MAXIMUM_OUTPUT_DETAILS).joinToString() + " (${differences.size} output entries)",
        )
    }

    /** Proves changed properties keys unobservable in both folded revisions; unknown loaders block. */
    fun resourcesUnreachable(
        methods: List<MethodSnapshot>, dependenciesAnalyzed: Boolean = false, entryReads: List<ResourceRead> = emptyList(),
    ): Boolean {
        if (base == null || head == null) return false
        val scopeKnown = base.resourceConsumersKnown && head.resourceConsumersKnown &&
            base.resourceEntries.keys == head.resourceEntries.keys &&
            dependenciesCovered(dependenciesAnalyzed, base, head)
        val reads = methods.flatMap { it.resourceReads } + entryReads
        val byteChanges = base.resourceIndependentEntries.filter { (entry, marker) ->
            marker.startsWith("properties:") && base.nonClassEntries[entry] != head.nonClassEntries[entry]
        }.values.map { it.removePrefix("properties:") }.toSet()
        return scopeKnown && base.resourceEntries.all { (path, old) ->
            val new = head.resourceEntries.getValue(path)
            val changedKeys = (old.keys + new.keys).filter { old[it] != new[it] }
            reads.none { read ->
                val bundle = read.bundle
                val matches = bundle == null || path == "$bundle.properties" || path.startsWith("${bundle}_")
                matches && (read.key == null && path in byteChanges || read.key in changedKeys)
            }
        }
    }

    fun reasons(code: ReasonCode, scenario: String, message: String): List<VerdictReason> = changes.map {
        VerdictReason(if (it in sourceChanges) emptySet() else setOf(ChangeKind.BUILD_INPUT_CHANGED),
            code, message, emptyList(), scenario, it.basePath, it.headPath)
    }

    /** Classifies an already failing comparison; every difference needs complete, located declaration evidence. */
    fun attributedSourceDifference(differences: List<String>, gaps: List<String>, confirmed: Set<String>): Boolean =
        differences.isNotEmpty() && gaps.isEmpty() && confirmed.containsAll(differences)

    /**
     * Compares original application classes after removing only GraphFold's verified provenance annotation.
     * Call only after both revisions' compiler provenance has been validated against their original classes.
     * Resource, dependency, and class-placement gaps still prevent this equality proof.
     */
    fun unchangedClasses(baseClasses: Path, headClasses: Path): Boolean =
        gap() == null && applicationClassIdentity(baseClasses) == applicationClassIdentity(headClasses)

    private companion object {
        const val MAXIMUM_OUTPUT_DETAILS = 20
    }
}

private fun applicationClassIdentity(directory: Path): Map<String, String> = Files.walk(directory).use { files ->
    files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().toList().associate { file ->
        val node = ClassNode()
        ClassReader(Files.readAllBytes(file)).accept(node, 0)
        node.visibleAnnotations?.removeAll { it.desc == ORIGIN_MARKER }
        node.invisibleAnnotations?.removeAll { it.desc == ORIGIN_MARKER }
        // A fresh constant pool removes the injected source paths without rewriting user string values.
        val writer = ClassWriter(0)
        node.accept(writer)
        directory.relativize(file).joinToString("/") to
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()))
    }
}

/** Both rename endpoints must belong to the JVM source model before declaration-level proof is applicable. */
internal fun ChangedFile.isJvmSourceChange(): Boolean = listOfNotNull(basePath, headPath).all {
    it.endsWith(".java") || it.endsWith(".kt")
}

private fun dependenciesCovered(analyzed: Boolean, base: ContentInventory, head: ContentInventory): Boolean =
    analyzed || base.resourceDependencies.isEmpty() && head.resourceDependencies.isEmpty()
