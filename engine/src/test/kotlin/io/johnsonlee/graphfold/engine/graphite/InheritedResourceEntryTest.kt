package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InheritedResourceEntryTest {
    @TempDir lateinit var directory: Path

    @Test fun `private constructor does not hide inherited static API or exposed fields`() {
        val dependency = compile("library", "Base", """
            package external;
            public class Base {
                public static final String FIELD = java.util.ResourceBundle.getBundle("messages").getString("inherited-field");
                public static String read() { return java.util.ResourceBundle.getBundle("messages").getString("inherited-method"); }
            }
        """.trimIndent())
        val application = compile("application", "App", """
            public class App extends external.Base {
                public static final String OWN = java.util.ResourceBundle.getBundle("messages").getString("own-field");
                private App() { java.util.ResourceBundle.getBundle("messages").getString("private-constructor"); }
            }
        """.trimIndent(), dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertEquals(setOf("App", "external.Base"), declarations.entryInitializers("App"))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        val reads = snapshot.resourceEntryReads.getValue("App")
        assertTrue(reads.containsAll(listOf(ResourceRead("messages", "inherited-field"), ResourceRead("messages", "inherited-method"),
            ResourceRead("messages", "own-field"))), reads.toString())
        assertFalse(reads.any { it.key == "private-constructor" })
    }

    @Test fun `private constructor and private static state alone do not activate a type`() {
        val application = compile("inactive", "Unused", """
            public class Unused {
                private static final String SECRET = java.util.ResourceBundle.getBundle("messages").getString("unused-initializer");
                private Unused() { java.util.ResourceBundle.getBundle("messages").getString("unused-constructor"); }
            }
        """.trimIndent())
        val declarations = ResourceDeclarations(listOf(application))
        assertTrue(declarations.entryInitializers("Unused").orEmpty().isEmpty())
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), dependencyResources = true)
        assertTrue(snapshot.resourceEntryReads.getValue("Unused").isEmpty())
        assertTrue(snapshot.methods.getValue("Unused.<init>()V").resourceReads.isNotEmpty())
    }

    @Test fun `inherited interface field activates its declaration without a concrete application method`() {
        val dependency = compile("interface-library", "Feature", """
            package external;
            public interface Feature {
                String FIELD = java.util.ResourceBundle.getBundle("messages").getString("interface-field");
                default String read() { return java.util.ResourceBundle.getBundle("messages").getString("interface-method"); }
            }
        """.trimIndent())
        val application = compile("interface-application", "Api", "public interface Api extends external.Feature {}", dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertEquals(setOf("external.Feature"), declarations.entryInitializers("Api"))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        assertTrue(snapshot.methods.isEmpty())
        assertTrue(snapshot.resourceEntryReads.getValue("Api").containsAll(listOf(
            ResourceRead("messages", "interface-field"), ResourceRead("messages", "interface-method"))))
    }

    private fun compile(name: String, type: String, source: String, dependency: Path? = null): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val classes = Files.createDirectories(root.resolve("classes"))
        val file = root.resolve("$type.java")
        Files.writeString(file, source)
        val arguments = listOf("-d", classes.toString()) + dependency?.let { listOf("-cp", it.toString()) }.orEmpty() + file.toString()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return classes
    }
}
