package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.inputs.CompilerEvidence
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.git.changedFile
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackagedSourceEqualityTest {
    @TempDir lateinit var temporary: Path
    private val change = changedFile("Excluded.java", "Excluded.java")

    @Test fun `unchanged packages need verified evidence on both revisions and an actual source change`() {
        val base = project("base")
        val head = project("head")
        val proof = assertNotNull(compare(base, head))
        assertTrue(proof.reasons(ReasonCode.BUILD_OUTPUT_UNCHANGED, "baseline", "identical").single().changes.isEmpty())
        assertNull(compare(base.copy(compilerEvidence = emptyList()), head))
        assertNull(compare(base, head.copy(compilerEvidence = emptyList())))
        assertNull(unchangedSourceInputs(base, head, temporary, temporary, emptyList(), emptySet()))
        val buildChange = changedFile("build.gradle", "build.gradle")
        assertNull(unchangedSourceInputs(base, head, temporary, temporary, listOf(buildChange), emptySet()))
    }

    @Test fun `changed packaged inventory and invalid provenance cannot borrow equality`() {
        val base = project("base")
        val head = project("head")
        assertNull(compare(base, head.copy(production = null)))
        assertNull(compare(base, head.copy(production = ContentInventory(mapOf("config" to "changed"), emptyMap()))))
        assertNull(compare(base.copy(originRunId = "stale"), head))
        assertNull(compare(base, head.copy(originRunId = "stale")))
    }

    private fun compare(base: StaticScope, head: StaticScope): InputChangeProof? =
        unchangedSourceInputs(base, head, temporary, temporary, listOf(change), setOf(change))

    private fun project(name: String): StaticScope {
        val classes = Files.createDirectories(temporary.resolve("$name/classes"))
        val manifests = Files.createDirectories(temporary.resolve("$name/origins"))
        val manifest = manifests.resolve("complete.json")
        Files.writeString(manifest, """
            {"schema":1,"complete":true,"runId":"maven-v3-test",
             "producer":{"compiler":"javac","backend":"JVM","version":"17"},"records":[],"unresolved":[]}
        """.trimIndent())
        return StaticScope(classes, emptyList(), emptyList(), manifests, "maven-v3-test",
            production = ContentInventory(mapOf("artifact/application.jar" to "same"), emptyMap()),
            compilerEvidence = listOf(CompilerEvidence(classes, manifest)))
    }
}
