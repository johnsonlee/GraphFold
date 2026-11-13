package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Only ASCII properties (including Java unicode escapes) have encoding-independent bundle semantics. */
internal class PropertyResources(roots: List<Path>) {
    private val hashes = linkedMapOf<String, String>()
    val values = linkedMapOf<String, Map<String, String>>()

    init {
        val candidates = roots.filter(Files::exists).flatMap { root ->
            Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { files ->
                files.filter { Files.isRegularFile(it) && it.toString().endsWith(".properties") }.toList().map {
                    root.relativize(it).joinToString("/") to Files.readAllBytes(it)
                }
            }
        }.groupBy({ it.first }, { it.second })
        candidates.forEach { (name, copies) ->
            val bytes = copies.first()
            if (copies.all { it.contentEquals(bytes) } && bytes.all { it >= 0 }) {
                val properties = runCatching { Properties().apply { load(bytes.inputStream()) } }.getOrNull()
                if (properties != null) {
                    hashes[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    values[name] = properties.stringPropertyNames().associateWith(properties::getProperty)
                }
            }
        }
    }

    fun identity(name: String, hash: String): String {
        return if (hashes[name] == hash) "properties:$name" else hash
    }
}
