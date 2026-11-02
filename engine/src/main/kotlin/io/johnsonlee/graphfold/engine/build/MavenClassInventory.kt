package io.johnsonlee.graphfold.engine.build

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Only compiler-recorded output ownership can authorize removing a retired class from an incremental build. */
internal class MavenClassInventory(private val checkout: Path, private val state: Path, private val directories: List<Path>) {
    private val mapper = jacksonObjectMapper()
    private val file = state.resolve("outputs.json")
    private val previous: List<JsonNode> = if (Files.exists(file)) mapper.readTree(file.toFile()).toList() else emptyList()

    fun prepare() {
        previous.filter { entry -> entry.path("sources").any { !Files.exists(Path.of(it.path("path").asText())) } }
            .forEach { entry ->
                checkBuild(entry.path("sources").all { !Files.exists(Path.of(it.path("path").asText())) },
                    "Partial deletion of a multi-source Maven output requires refreshed compiler ownership")
                retire(entry)
            }
    }

    fun reconcile(pending: Path) {
        val documents = regularFiles(pending).filter { it.toString().endsWith(".json") }.map { mapper.readTree(it.toFile()) }
        checkBuild(documents.all { it.path("schema").asInt() == 1 && it.path("complete").asBoolean() },
            "Incomplete Maven compiler output ownership")
        val emitted = documents.flatMap { it.path("outputs").toList() }
        val fresh = emitted.associateBy { it.path("owner").asText() }
        checkBuild(fresh.size == emitted.size, "Duplicate Maven compiler output ownership")
        val compiledSources = emitted.flatMap { it.path("sources").map { source -> source.path("path").asText() } }.toSet()
        previous.filter { it.path("owner").asText() !in fresh && it.path("sources").any { source ->
            source.path("path").asText() in compiledSources
        } }.forEach(::retire)
        val inventory = (previous.associateBy { it.path("owner").asText() } + fresh).values.mapNotNull { entry ->
            val owner = entry.path("owner").asText()
            val matches = directories.map { it.resolve(owner.replace('.', '/') + ".class") }.filter(Files::isRegularFile)
            checkBuild(matches.size <= 1, "Duplicate Maven class output: $owner")
            matches.singleOrNull()?.let { binary -> validate(entry, binary, owner in fresh) }
        }
        val owned = inventory.map { it.path("classFile").asText() }.toSet()
        val actual = directories.filter(Files::isDirectory).flatMap(::regularFiles)
            .filter { it.toString().endsWith(".class") }.map { checkout.relativize(it).toString() }.toSet()
        checkBuild(actual == owned, "Maven build contains classes without verified compiler ownership: ${actual - owned}")
        Files.createDirectories(state)
        val temporary = state.resolve("outputs.tmp")
        mapper.writeValue(temporary.toFile(), inventory)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun validate(entry: JsonNode, binary: Path, fresh: Boolean): ObjectNode {
        checkBuild(entry.path("sources").isArray && !entry.path("sources").isEmpty,
            "Maven compiler output has no physical source ownership: $binary")
        entry.path("sources").forEach { source ->
            val path = Path.of(source.path("path").asText()).toAbsolutePath().normalize()
            checkBuild(Files.isRegularFile(path) && path.toRealPath().startsWith(checkout.toRealPath()) &&
                digest(path) == source.path("hash").asText(), "Stale Maven output source ownership: $path")
        }
        checkBuild(binary.toRealPath().startsWith(checkout.toRealPath()), "Maven class output escapes checkout")
        val hash = digest(binary)
        checkBuild(fresh || entry.path("classHash").asText() == hash,
            "Maven class changed without compiler output ownership: $binary")
        return entry.deepCopy<ObjectNode>().put("classFile", checkout.relativize(binary).toString()).put("classHash", hash)
    }

    private fun retire(entry: JsonNode) {
        val binary = checkout.resolve(entry.path("classFile").asText()).normalize()
        val relative = entry.path("owner").asText().replace('.', '/') + ".class"
        checkBuild(directories.any { it.resolve(relative).normalize() == binary } && binary.startsWith(checkout),
            "Invalid Maven owned class path")
        if (Files.exists(binary)) {
            checkBuild(binary.toRealPath().startsWith(checkout.toRealPath()), "Maven owned output escapes checkout")
            checkBuild(digest(binary) == entry.path("classHash").asText(), "Refusing to remove changed Maven output: $binary")
            Files.delete(binary)
        }
        // Kotlin's incremental FileCopier can restore a deleted target/classes file from this owned cache entry.
        val module = directories.firstOrNull { binary.startsWith(it) }?.parent
        val cached = module?.resolve("kotlin-ic/compile/classes")?.resolve(entry.path("owner").asText().replace('.', '/') + ".class")
        if (cached != null && Files.exists(cached)) {
            checkBuild(cached.toRealPath().startsWith(checkout.toRealPath()), "Kotlin incremental output escapes checkout")
            checkBuild(digest(cached) == entry.path("classHash").asText(), "Refusing to remove changed Kotlin incremental output")
            Files.delete(cached)
        }
    }
}
