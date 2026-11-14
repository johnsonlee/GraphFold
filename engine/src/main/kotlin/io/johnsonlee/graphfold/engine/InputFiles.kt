package io.johnsonlee.graphfold.engine

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Content identities used to validate source provenance against already prepared static inputs. */
internal fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }

internal fun regularFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) }.sorted().toList()
}

internal fun deleteDirectory(path: Path) {
    if (Files.exists(path, NOFOLLOW_LINKS)) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
