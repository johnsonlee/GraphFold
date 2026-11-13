package io.johnsonlee.graphfold.gradle

import java.nio.file.Files
import java.nio.file.Path

/** Retain real BASE/HEAD package validation without launching either application. */
internal fun packagedFlagrArtifacts(cache: Path, boot: Boolean): List<Path> {
    val name = if (boot) "app-1.jar" else "app-1.war"
    val paths = Files.walk(cache.resolve("export/git/workspaces")).use { entries -> entries.filter {
        it.fileName.toString() == name && it.parent.fileName.toString() == "libs"
    }.toList() }
    return listOf("base", "head").map { revision ->
        requireNotNull(paths.singleOrNull { path -> path.any { it.toString() == revision } }) {
            "Expected one packaged $revision $name, found: $paths"
        }
    }
}
