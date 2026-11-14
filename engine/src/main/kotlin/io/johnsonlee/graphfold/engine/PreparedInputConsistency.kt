package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A Git no-op cannot certify different sets of supplied program bytes as unchanged. */
internal fun validateUnchangedGitInputs(base: PreparedRevision, head: PreparedRevision) {
    require(base.scopes.keys == head.scopes.keys) { "An empty Git diff has different static input scopes" }
    require(resourceBytes(base) == resourceBytes(head)) { "An empty Git diff has different resource input bytes" }
    base.scopes.forEach { (name, scope) ->
        require(identicalStaticScopeBytes(scope, head.scopes.getValue(name))) {
            "An empty Git diff has different static input bytes in scope $name"
        }
    }
}

/** Compares original prepared bytes in classpath order, independently of claimed inventory digests. */
internal fun identicalStaticScopeBytes(base: StaticScope, head: StaticScope): Boolean = scopeBytes(base) == scopeBytes(head)

private fun resourceBytes(revision: PreparedRevision): List<Map<String, String>> =
    revision.inventory?.resourceDependencies.orEmpty().map(::inputBytes)

private data class ScopeBytes(
    val classes: Map<String, String>,
    val dependencies: List<Map<String, String>>,
    val resources: List<Map<String, String>>,
)

private fun scopeBytes(scope: StaticScope): ScopeBytes = ScopeBytes(inputBytes(scope.classes),
    scope.dependencies.map(::inputBytes), scope.production?.resourceDependencies.orEmpty().map(::inputBytes))

private fun inputBytes(path: Path): Map<String, String> {
    require(Files.exists(path)) { "Prepared static input does not exist: $path" }
    return if (Files.isDirectory(path)) Files.walk(path).use { files ->
        files.filter(Files::isRegularFile).toList().associate { path.relativize(it).joinToString("/") to byteDigest(it) }
    } else mapOf("" to byteDigest(path))
}

private fun byteDigest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = stream.read(buffer)
        while (count >= 0) {
            digest.update(buffer, 0, count)
            count = stream.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
