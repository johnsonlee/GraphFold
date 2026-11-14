package io.johnsonlee.graphfold.engine.inputs

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.inputs.archive.checkInput
import io.johnsonlee.graphfold.engine.inputs.archive.digest
import java.nio.file.Files
import java.nio.file.Path

internal data class SnapshotRecord(
    val version: Int = 1, val sha: String, val repositoryRoot: Path, val storedDirectory: Path,
    val scopes: List<ArchiveScopeInput>, val hashes: Map<String, String>, val assetDirectory: Path,
)

internal val snapshotMapper = jacksonObjectMapper().registerModule(SimpleModule().apply {
    addSerializer(Path::class.java, object : JsonSerializer<Path>() {
        override fun serialize(value: Path, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(value.toString())
        }
    })
    addDeserializer(Path::class.java, object : JsonDeserializer<Path>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Path = Path.of(parser.valueAsString)
    })
    addKeyDeserializer(Path::class.java, object : KeyDeserializer() {
        override fun deserializeKey(key: String, context: DeserializationContext): Path = Path.of(key)
    })
})

internal class SnapshotAssets(private val directory: Path, private val assets: Path) {
    private val copies = linkedMapOf<Path, Path>()

    fun copy(path: Path): Path = copies.getOrPut(path.toAbsolutePath().normalize()) {
        val absolute = path.toAbsolutePath().normalize()
        val target = assets.resolve(absolute.root.toString().hashCode().toUInt().toString())
            .resolve(absolute.root.relativize(absolute))
        Files.createDirectories(target.parent)
        if (Files.isDirectory(path)) {
            Files.walk(path).use { files -> files.forEach { file ->
                val destination = target.resolve(path.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(destination)
                else if (!Files.exists(destination)) Files.copy(file, destination)
            } }
        } else if (Files.exists(path) && !Files.exists(target)) Files.copy(path, target)
        target
    }

    fun hashes(): Map<String, String> = Files.walk(assets).use { paths ->
        paths.sorted().toList().associate {
            directory.relativize(it).joinToString("/") to snapshotHash(it)
        }
    }
}

internal fun validateSnapshotScopes(scopes: List<ArchiveScopeInput>) {
    checkInput(scopes.isNotEmpty() && scopes.map { it.id }.distinct().size == scopes.size,
        "Static input scope identities must be nonempty and unique")
    checkInput(scopes.all { it.id.isNotBlank() }, "Static input scope identity must not be blank")
}

internal fun readSnapshot(manifest: Path): SnapshotRecord {
    val record = snapshotMapper.readValue(manifest.toFile(), SnapshotRecord::class.java)
    checkInput(record.version == 1, "Unsupported static input snapshot format: ${record.version}")
    validateSnapshotScopes(record.scopes)
    val directory = manifest.toAbsolutePath().normalize().parent
    val assetRoot = directory.resolve(record.storedDirectory.relativize(record.assetDirectory)).normalize()
    checkInput(assetRoot.startsWith(directory) && Files.isDirectory(assetRoot), "Snapshot asset directory is missing")
    val actualNames = Files.walk(assetRoot).use { paths ->
        paths.map { directory.relativize(it).joinToString("/") }.toList().toSet()
    }
    checkInput(actualNames == record.hashes.keys, "Static input snapshot asset inventory changed")
    record.hashes.forEach { (name, hash) ->
        val file = directory.resolve(name).normalize()
        // The normalized asset root is inside directory; exact walk-derived keys exclude absolute or parent paths.
        // A concurrent disappearance fails during hashing.
        checkInput(snapshotHash(file) == hash,
            "Static input snapshot content changed or is missing: $name")
    }
    fun asset(path: Path): Path {
        checkInput(path.startsWith(record.storedDirectory), "Snapshot asset is outside its recorded directory: $path")
        return directory.resolve(record.storedDirectory.relativize(path)).normalize().also {
            checkInput(it.startsWith(directory), "Snapshot asset escapes its directory: $path")
        }
    }
    return record.copy(scopes = record.scopes.map { scope -> scope.copy(
        archive = asset(scope.archive), dependencies = scope.dependencies.map(::asset),
        sourceOutputs = scope.sourceOutputs.mapValues { (_, paths) -> paths.map(::asset) },
        compilerEvidence = scope.compilerEvidence.map { it.copy(classes = asset(it.classes), manifest = asset(it.manifest)) },
        originDirectory = scope.originDirectory?.let(::asset),
        sourceClasspaths = scope.sourceClasspaths.mapValues { (_, paths) -> paths.map(::asset) },
        projectArtifacts = scope.projectArtifacts.mapKeys { asset(it.key) }.mapValues { (_, paths) -> paths.map(::asset) },
    ) })
}

private fun snapshotHash(path: Path): String = if (Files.isDirectory(path)) "directory" else digest(path)
