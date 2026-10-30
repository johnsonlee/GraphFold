package io.johnsonlee.graphfold.cli

import java.nio.file.Files
import java.nio.file.Path

internal data class FixtureRevision(val repository: Path, val base: String, val head: String, val fold: Path)

internal fun createFixture(directory: Path, name: String): FixtureRevision {
    val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()
    val repository = Files.createDirectories(directory.resolve("repository"))
    fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }
    git("init", "--quiet")
    git("config", "user.name", "Test")
    git("config", "user.email", "test@example.invalid")
    val sources = Files.createDirectories(repository.resolve("src/main/java/sample"))
    Files.copy(fixtures.resolve("common/Ab.java"), sources.resolve("Ab.java"))
    Files.copy(fixtures.resolve("cases/$name/base/Example.java"), sources.resolve("Example.java"))
    git("add", ".")
    git("commit", "--quiet", "-m", "base")
    val base = git("rev-parse", "HEAD")
    Files.copy(fixtures.resolve("cases/$name/head/Example.java"), sources.resolve("Example.java"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    git("add", ".")
    git("commit", "--quiet", "-m", "head")
    return FixtureRevision(repository, base, git("rev-parse", "HEAD"), fixtures.resolve("fold.yml"))
}
