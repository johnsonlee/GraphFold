package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

/** Matching application copies retain ownership without changing the already selected classpath definition. */
internal fun copyApplicationClasses(effective: List<Path>, applications: List<Path>, output: Path, variantThreshold: Int) {
    val owned = mutableMapOf<String, MutableSet<String>>()
    applications.forEach { artifact -> visitPackagedClasses(artifact, variantThreshold) { name, bytes ->
        owned.getOrPut(name) { mutableSetOf() }.add(classIdentity(bytes))
    } }
    effective.forEach { artifact -> visitPackagedClasses(artifact, variantThreshold, owned.keys) { name, bytes ->
        if (classIdentity(bytes) in owned.getValue(name)) {
            val target = output.resolve(name).normalize()
            checkInput(target.startsWith(output) && output.relativize(target).joinToString("/") == name,
                "Unsupported packaged class path: $name")
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    } }
}

private fun visitPackagedClasses(
    artifact: Path, variantThreshold: Int, names: Set<String>? = null, visit: (String, ByteArray) -> Unit,
) {
    JarFile(artifact.toFile()).use { jar ->
        staticArchiveEntries(jar, variantThreshold).filterKeys { name ->
            name.endsWith(".class") && (names == null || name in names)
        }.forEach { (name, entry) -> visit(name, jar.getInputStream(entry).use { it.readBytes() }) }
    }
}

private fun classIdentity(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
