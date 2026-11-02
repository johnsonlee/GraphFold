package io.johnsonlee.graphfold.engine.build

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Retires only unchanged processed files previously attributed to declared Maven resource inputs. */
internal class MavenResourceInventory(private val state: Path, private val roots: List<Pair<Path, Path>>) {
    private val mapper = jacksonObjectMapper()
    private val file = state.resolve("production-resources.json")
    private val copies = linkedMapOf<Path, Path>()

    fun prepare() {
        roots.filter { Files.isDirectory(it.first) }.forEach { (source, output) ->
            val inputs = Files.walk(source, FileVisitOption.FOLLOW_LINKS).use { paths ->
                paths.filter(Files::isRegularFile).sorted().toList()
            }
            inputs.forEach { input ->
                val destination = output.resolve(source.relativize(input)).toAbsolutePath().normalize()
                checkBuild(copies.put(destination, input.toAbsolutePath().normalize()) == null,
                    "Overlapping Maven resource mappings require explicit output ownership: $destination")
            }
        }
        if (!Files.exists(file)) return
        mapper.readTree(file.toFile()).forEach { record ->
            val output = Path.of(record.path("output").asText()).toAbsolutePath().normalize()
            val source = Path.of(record.path("source").asText()).toAbsolutePath().normalize()
            checkBuild(roots.any { output.startsWith(it.second.toAbsolutePath().normalize()) },
                "Maven resource ownership escapes production outputs: $output")
            if (copies[output] != source && Files.exists(output)) {
                checkBuild(roots.any { output.startsWith(it.second.toAbsolutePath().normalize()) &&
                    output.toRealPath().startsWith(it.second.toRealPath()) } &&
                    digest(output) == record.path("hash").asText(), "Refusing to remove changed Maven resource output: $output")
                Files.delete(output)
            }
        }
    }

    fun publish() {
        val records = copies.map { (output, source) ->
            checkBuild(Files.isRegularFile(output), "Maven did not produce a declared resource: $source")
            mapper.createObjectNode().put("source", source.toString()).put("output", output.toString()).put("hash", digest(output))
        }
        Files.createDirectories(state)
        val temporary = state.resolve("production-resources.tmp")
        mapper.writeValue(temporary.toFile(), records)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
