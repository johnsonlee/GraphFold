package io.johnsonlee.graphfold.engine.build

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A successful Maven build publishes one immutable, class-bound view of retained and newly emitted origins. */
internal class MavenProvenance(private val state: Path, private val runId: String) {
    val pending: Path = state.resolve("origins")
    private val snapshot = state.resolve("snapshot.json")
    private val mapper = jacksonObjectMapper()

    fun prepare() {
        Files.createDirectories(pending)
        regularFiles(pending).forEach(Files::delete)
    }

    fun publish(classes: Path, output: Path) {
        val records = linkedMapOf<String, Pair<JsonNode, ObjectNode>>()
        val unresolved = linkedMapOf<String, Pair<JsonNode, JsonNode>>()
        val fresh = mutableSetOf<String>()
        val old = if (Files.exists(snapshot)) mapper.readTree(snapshot.toFile()).toList() else emptyList()
        val emitted = regularFiles(pending).filter { it.toString().endsWith(".json") }.map { mapper.readTree(it.toFile()) }
        (old + emitted).forEachIndexed { index, document ->
            checkBuild(document.path("schema").asInt() == 1 && document.path("complete").asBoolean() &&
                document.path("runId").asText() == runId, "Incomplete or stale Maven compiler provenance")
            val producer = document.path("producer")
            document.path("records").forEach { record ->
                val owner = record.path("owner").asText()
                if (index >= old.size) checkBuild(fresh.add(owner), "Duplicate emitted Maven origin: $owner")
                records[owner] = producer to record.deepCopy<ObjectNode>()
                unresolved.remove(owner)
            }
            document.path("unresolved").forEach { record ->
                val owner = record.path("owner").asText()
                unresolved[owner] = producer to record
                records.remove(owner)
            }
        }
        records.entries.removeIf { (owner, value) ->
            val binary = classes.resolve(owner.replace('.', '/') + ".class")
            if (!Files.isRegularFile(binary)) true else {
                val record = value.second
                val source = Path.of(record.path("source").asText())
                checkBuild(Files.isRegularFile(source) && digest(source) == record.path("sourceHash").asText(),
                    "Stale Maven compiler source provenance: $source")
                val hash = digest(binary)
                checkBuild(owner in fresh || record.path("classHash").asText() == hash,
                    "Changed Maven class has no refreshed provenance: $owner")
                record.put("classHash", hash)
                false
            }
        }
        unresolved.keys.removeIf { !Files.isRegularFile(classes.resolve(it.replace('.', '/') + ".class")) }
        val producers = (old + emitted).map { it.path("producer") }.distinct()
        val documents = producers.map { producer ->
            mapper.createObjectNode().put("schema", 1).put("complete", true).put("runId", runId).apply {
                set<JsonNode>("producer", producer)
                putArray("records").addAll(records.values.filter { it.first == producer }.map { it.second })
                putArray("unresolved").addAll(unresolved.values.filter { it.first == producer }.map { it.second })
            }
        }
        Files.createDirectories(output)
        documents.forEachIndexed { index, document -> mapper.writeValue(output.resolve("$index.json").toFile(), document) }
        val temporary = state.resolve("snapshot.tmp")
        mapper.writeValue(temporary.toFile(), documents)
        Files.move(temporary, snapshot, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }
