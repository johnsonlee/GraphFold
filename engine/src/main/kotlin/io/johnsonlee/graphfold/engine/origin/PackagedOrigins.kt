package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.JsonNode
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.digest
import io.johnsonlee.graphfold.engine.regularFiles
import java.nio.file.Files
import java.nio.file.Path

/** Validate compiler outputs first; packaging can remove evidence but cannot invent or rewrite it. */
internal fun readPackagedOrigins(project: StaticScope, root: Path, readManifest: (Path) -> JsonNode): OriginCatalog {
    val outputs = project.compilerEvidence.groupBy { it.classes.toAbsolutePath().normalize() }
    val catalogs = outputs.mapValues { (classes, evidence) ->
        val compiler = project.copy(classes = classes, originDirectory = evidence.first().manifest.parent,
            compilerEvidence = emptyList())
        OriginReader(compiler, root, readManifest, evidence.map { it.manifest }).read()
    }
    val diagnostics = catalogs.flatMap { (output, catalog) -> catalog.diagnostics.map { "$output: $it" } }.toMutableList()
    val origins = mutableListOf<SourceOrigin>()
    val owners = linkedSetOf<String>()
    regularFiles(project.classes).filter { it.toString().endsWith(".class") }.forEach { packaged ->
        val relative = project.classes.relativize(packaged)
        val owner = relative.toString().removeSuffix(".class").replace('/', '.').replace('\\', '.')
        owners += owner
        val matching = outputs.keys.filter { output ->
            val binary = output.resolve(relative)
            Files.isRegularFile(binary) && digest(binary) == digest(packaged)
        }
        when (matching.size) {
            0 -> diagnostics += "Packaged class has no byte-identical compiler evidence: $owner"
            1 -> origins += catalogs.getValue(matching.single()).origins.filter { it.owner == owner }
            else -> diagnostics += "Packaged class has ambiguous compiler outputs: $owner"
        }
    }
    return OriginCatalog(origins, diagnostics, owners, catalogs.values.flatMap { it.inlineEvidence })
}
