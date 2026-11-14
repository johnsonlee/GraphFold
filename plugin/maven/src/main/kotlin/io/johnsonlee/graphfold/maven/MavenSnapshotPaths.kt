package io.johnsonlee.graphfold.maven

import io.johnsonlee.graphfold.engine.inputs.StaticInputSnapshots
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.apache.maven.project.MavenProject

/** Module identity is stable when a reactor changes its release version. */
internal fun MavenProject.moduleDirectory(root: Path): Path = root.resolve("modules")
    .resolve(URLEncoder.encode("$groupId:$artifactId", UTF_8))

internal fun MavenProject.moduleSnapshot(root: Path): Path = moduleDirectory(root).resolve("snapshot.json")

/** A directory supplies matching reactor inputs; an explicit manifest retains the standalone workflow. */
internal fun MavenProject.baseSnapshot(input: Path): Path {
    val manifest = if (Files.isDirectory(input)) {
        val module = moduleSnapshot(input)
        if (Files.isRegularFile(module)) module else input.resolve("snapshot.json")
    } else input
    val identities = StaticInputSnapshots.scopeIds(manifest)
    require(identities == listOf("$groupId:$artifactId")) {
        "BASE snapshot $manifest belongs to $identities, expected $groupId:$artifactId"
    }
    return manifest
}
