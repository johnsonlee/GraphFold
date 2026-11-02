package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalObjectEscapeInheritanceTest {
    @TempDir lateinit var directory: Path

    @Test fun `external interface default methods prevent bounding a final DTO by its declared body alone`() {
        val source = directory.resolve("Entry.java")
        Files.writeString(source, """
            public class Entry {
                public static void expose(Dto value) { Encoder.encode(value); }
            }
            final class Dto implements ExternalObserver {}
            interface ExternalObserver {
                default Class<?> inspect(String name) throws Exception { return Class.forName(name); }
            }
            class Encoder { static void encode(Object value) {} }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", directory.toString(), source.toString()))
        val application = listOf("Entry", "Dto").associateWith { name -> ClassNode().also {
            ClassReader(Files.readAllBytes(directory.resolve("$name.class"))).accept(it, 0)
        } }
        assertTrue(application.getValue("Dto").methods.none { it.name == "inspect" })
        val gap = ExternalObjectEscape(application).methodDiagnostics().getValue("Entry.expose(LDto;)V").single()
        assertTrue(gap.globalObservation)
        assertTrue(gap.observedOwners.isEmpty())
    }
}
