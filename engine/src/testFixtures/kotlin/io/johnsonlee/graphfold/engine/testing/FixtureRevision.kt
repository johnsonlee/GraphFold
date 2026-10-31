package io.johnsonlee.graphfold.engine.testing

import java.nio.file.Files
import java.nio.file.Path

/**
 * A committed fixture repository and the inputs needed to analyze its change.
 *
 * @property repository directory containing the fixture's Git repository.
 * @property base SHA of the commit containing the fixture's baseline Java source.
 * @property head SHA of the commit containing the fixture's changed Java source.
 * @property fold path to the shared YAML fold rules used by the fixture corpus.
 */
data class FixtureRevision(val repository: Path, val base: String, val head: String, val fold: Path)

/**
 * Creates a two-commit Java fixture repository using the Git command-line executable.
 *
 * Fixture files are resolved from `../fixtures` relative to the test process's working directory.
 * The repository is created beneath [directory] with local test author settings.
 *
 * @param directory parent directory for a new `repository` child directory.
 * @param name Java fixture name under `fixtures/cases`, with `base/Example.java` and `head/Example.java` sources.
 * @return committed revisions and shared fold-rule path for use by engine and frontend integration tests.
 * @throws IllegalStateException if a Git command fails, including when the head source produces no new commit.
 */
fun createFixture(directory: Path, name: String): FixtureRevision {
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
