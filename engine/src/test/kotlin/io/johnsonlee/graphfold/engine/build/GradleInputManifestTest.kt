package io.johnsonlee.graphfold.engine.build

import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GradleInputManifestTest {
    @Test
    fun `all exported kinds decode paths without changing the wire protocol`() {
        val classes = Path.of("/project with spaces/build/classes")
        val source = Path.of("/project with spaces/src/main/java")
        val dependency = Path.of("/cache/library.jar")
        val entries = parseGradleInputs(listOf(
            "classes|${encode(classes)}",
            "source|${encode(source)}",
            "dependency|${encode(dependency)}",
            "sourceOutput|${encode(source)}|${encode(classes)}",
        ))
        assertEquals(GradleInputKind.entries, entries.map { it.kind })
        assertEquals(listOf(listOf(classes), listOf(source), listOf(dependency), listOf(source, classes)), entries.map { it.paths })
        // The independently executed init script must emit the same tags as the typed reader.
        val script = javaClass.getResource("graphfold.gradle").readText()
        GradleInputKind.entries.forEach { kind -> assertTrue(script.contains("${kind.name}('${kind.tag}')")) }
    }

    @Test
    fun `unknown kinds and incorrect field counts fail instead of dropping exported inputs`() {
        val path = encode(Path.of("/project/classes"))
        listOf("unknown|$path", "Classes|$path", "sourceOutputExtra|$path", "").forEach { line ->
            assertFailsWith<BuildFailure>(line) { parseGradleInputs(listOf(line)) }
        }
        listOf("classes", "classes|$path|$path", "sourceOutput|$path", "sourceOutput|$path|$path|$path").forEach { line ->
            assertFailsWith<BuildFailure>(line) { parseGradleInputs(listOf(line)) }
        }
        assertFailsWith<IllegalArgumentException> { parseGradleInputs(listOf("dependency|invalid base64!")) }
        assertTrue(parseGradleInputs(emptyList()).isEmpty())
    }

    private fun encode(path: Path): String = Base64.getEncoder().encodeToString(path.toString().toByteArray(Charsets.UTF_8))
}
