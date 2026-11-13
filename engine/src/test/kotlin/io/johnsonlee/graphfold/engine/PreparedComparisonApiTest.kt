package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreparedComparisonApiTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `public prepared input API derives actual changed lines from Git commits`(controlled: Boolean) {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        val source = repository.resolve("Example.java")
        Files.writeString(source, source(1, 1))
        Git.init().setDirectory(repository.toFile()).call().close()
        val baseSha = commit(repository)
        Files.writeString(source, source(if (controlled) 2 else 1, if (controlled) 1 else 2))
        val headSha = commit(repository)
        val baseCheckout = temporary.resolve("base")
        Git.cloneRepository().setURI(repository.toUri().toString()).setDirectory(baseCheckout.toFile()).call().use {
            it.checkout().setName(baseSha).call()
        }
        val base = revision(baseSha, baseCheckout, "base")
        val head = revision(headSha, repository, "head")
        val engine = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis")))
        val result = if (controlled) engine.analyze(engine.prepare(base, head, FOLD)) else engine.analyze(base, head, FOLD)
        assertEquals(if (controlled) Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.ruleAudits.isNotEmpty() && result.ruleAudits.all { (it.selectedCount ?: 0) > 0 }, result.toString())
        assertEquals(!controlled, result.findings.isNotEmpty(), result.toString())
        assertFailsWith<IllegalArgumentException> { engine.prepare(base.copy(sha = headSha), head, FOLD) }
        assertFailsWith<IllegalArgumentException> { engine.prepare(base, head.copy(sha = baseSha), FOLD) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["classes-null", "classes-claimed", "dependency", "dependency-order", "resources", "mapping"])
    fun `same Git revision cannot hide changed input bytes behind missing or identical inventory`(mode: String) {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        Files.writeString(repository.resolve("Example.java"), source(1, 1))
        Git.init().setDirectory(repository.toFile()).call().close()
        val sha = commit(repository)
        var base = revision(sha, repository, "base")
        var head = revision(sha, repository, "head")
        val original = base.scopes.getValue("application")
        var changed = head.scopes.getValue("application")
        if (mode.startsWith("classes")) {
            val replacement = Files.createDirectories(temporary.resolve("replacement")).resolve("Example.java")
            Files.writeString(replacement, source(2, 1))
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", changed.classes.toString(), replacement.toString()))
            val claimed = if (mode == "classes-null") null else base.inventory
            base = base.copy(inventory = claimed, scopes = mapOf("application" to original.copy(production = claimed)))
            head = head.copy(inventory = claimed, scopes = mapOf("application" to changed.copy(production = claimed)))
        } else if (mode.startsWith("dependency") || mode == "resources") {
            val before = resourceArchive("before", "before")
            val after = resourceArchive("after", "after")
            if (mode.startsWith("dependency")) {
                val oldDependencies = if (mode == "dependency-order") listOf(before, after) else listOf(before)
                val newDependencies = if (mode == "dependency-order") listOf(after, before) else listOf(after)
                base = base.copy(scopes = mapOf("application" to original.copy(dependencies = oldDependencies)))
                head = head.copy(scopes = mapOf("application" to changed.copy(dependencies = newDependencies)))
            } else {
                base = base.copy(inventory = requireNotNull(base.inventory).copy(resourceDependencies = listOf(before)))
                head = head.copy(inventory = requireNotNull(head.inventory).copy(resourceDependencies = listOf(after)))
            }
        } else {
            changed = changed.copy(sourceRoots = emptyList(), sourceOutputs = emptyMap(),
                originDirectory = temporary.resolve("missing-optional-mapping"))
            head = head.copy(scopes = mapOf("application" to changed))
        }
        val engine = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis")))
        if (mode == "mapping") {
            assertEquals(Verdict.PASS, engine.analyze(base, head, FOLD).verdict)
        } else {
            assertFailsWith<IllegalArgumentException> { engine.prepare(base, head, FOLD) }
        }
    }

    @Test fun `an empty commit cannot conceal changed prepared bytes`() {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        Files.writeString(repository.resolve("Example.java"), source(1, 1))
        Git.init().setDirectory(repository.toFile()).call().close()
        val baseSha = commit(repository)
        val headSha = Git.open(repository.toFile()).use { git ->
            git.commit().setAllowEmpty(true).setMessage("empty revision").setAuthor("GraphFold", "test@example.invalid")
                .setCommitter("GraphFold", "test@example.invalid").call().name
        }
        val baseCheckout = temporary.resolve("base")
        Git.cloneRepository().setURI(repository.toUri().toString()).setDirectory(baseCheckout.toFile()).call().use {
            it.checkout().setName(baseSha).call()
        }
        val base = revision(baseSha, baseCheckout, "base")
        val head = revision(headSha, repository, "head")
        val engine = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis")))
        val prepared = engine.prepare(base, head, FOLD)
        assertEquals(Verdict.PASS, engine.analyze(prepared).verdict)
        val replacement = Files.createDirectories(temporary.resolve("replacement")).resolve("Example.java")
        Files.writeString(replacement, source(2, 1))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", head.scopes.getValue("application").classes.toString(), replacement.toString()))
        assertFailsWith<IllegalArgumentException> { engine.prepare(base, head, FOLD) }
        val result = engine.analyze(prepared)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.blocking }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-git", "unborn", "scope-membership", "missing-classes", "missing-dependency"])
    fun `prepared API rejects incomplete revision identity and missing static bytes before proof`(mode: String) {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        Files.writeString(repository.resolve("Example.java"), source(1, 1))
        Git.init().setDirectory(repository.toFile()).call().close()
        val sha = commit(repository)
        val base = revision(sha, repository, "base")
        var head = revision(sha, repository, "head")
        val scope = head.scopes.getValue("application")
        val expected = when (mode) {
            "no-git", "unborn" -> {
                val checkout = Files.createDirectory(temporary.resolve("unbound"))
                if (mode == "unborn") Git.init().setDirectory(checkout.toFile()).call().close()
                head = head.copy(sourceDirectory = checkout)
                if (mode == "no-git") "not a Git repository" else "does not match Git revision"
            }
            "scope-membership" -> {
                head = head.copy(scopes = mapOf("other" to scope))
                "different static input scopes"
            }
            else -> {
                val absent = temporary.resolve("missing-bytes")
                val broken = if (mode == "missing-classes") scope.copy(classes = absent)
                    else scope.copy(dependencies = listOf(absent))
                head = head.copy(scopes = mapOf("application" to broken))
                "Prepared static input does not exist"
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).prepare(base, head, FOLD)
        }
        assertTrue(expected in failure.message.orEmpty(), failure.toString())
    }

    private fun resourceArchive(name: String, content: String): Path = temporary.resolve("$name.jar").also { path ->
        JarOutputStream(Files.newOutputStream(path)).use { archive ->
            archive.putNextEntry(JarEntry("configuration.properties"))
            archive.write(content.toByteArray())
            archive.closeEntry()
        }
    }

    private fun revision(sha: String, checkout: Path, name: String): PreparedRevision {
        val classes = Files.createDirectories(temporary.resolve("classes-$name"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), checkout.resolve("Example.java").toString()))
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classes.resolve("Example.class")))
            .joinToString("") { "%02x".format(it) }
        val inventory = ContentInventory(mapOf("class/Example.class" to digest), emptyMap())
        val scope = StaticScope(classes, listOf(checkout), emptyList(), sourceOutputs = mapOf(checkout to listOf(classes)),
            production = inventory)
        return PreparedRevision(sha, checkout, mapOf("application" to scope), inventory)
    }

    private fun source(controlled: Int, uncontrolled: Int) = """
        public class Example {
            public static boolean enabled() { return true; }
            public int value() { return enabled() ? $controlled : $uncontrolled; }
        }
    """.trimIndent()

    private fun commit(repository: Path) = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call().name
    }

    private companion object {
        val FOLD = """
            version: 1
            folds:
              - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                value: false
        """.trimIndent()
    }
}
