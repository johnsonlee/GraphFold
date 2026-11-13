package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.ResourceDeclarations

import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InheritedResourceIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `inherited dependency API reads are attached to exposed application type only`() {
        val dependency = library()
        val application = application("public class App extends external.Base { public int own() { return 1; } }", dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        val inherited = declarations.inheritedMethods("App").orEmpty()
        assertTrue("external.Base.inheritedRead()Ljava/lang/String;" in inherited)
        assertTrue("external.Base.protectedRead()Ljava/lang/String;" in inherited)
        assertTrue("external.Base.staticRead()Ljava/lang/String;" in inherited)
        assertTrue(inherited.none { ".hiddenRead(" in it || ".packageRead(" in it || ".<init>(" in it })
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        val reads = snapshot.methods.getValue("App.own()I").resourceReads.toSet()
        assertTrue(reads.containsAll(setOf(ResourceRead("messages", "inherited"), ResourceRead("messages", "protected"),
            ResourceRead("messages", "static"))), reads.toString())
        assertFalse(reads.any { it.key in setOf("unrelated", "private", "package") }, reads.toString())
        assertTrue(snapshot.methods.keys.none { it.startsWith("external.") })
    }

    @Test fun `direct overrides and static hiding mask inaccessible base implementations`() {
        val dependency = library()
        val application = application("""
            public class App extends external.Base {
                public String inheritedRead() { return "safe"; }
                protected String protectedRead() { return "safe"; }
                public static String staticRead() { return "safe"; }
            }
        """.trimIndent(), dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertTrue(declarations.inheritedMethods("App").orEmpty().isEmpty())
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        assertTrue(snapshot.methods.values.all { it.resourceReads.isEmpty() }, snapshot.methods.toString())
    }

    @Test fun `same package inherited methods remain callable application APIs`() {
        val dependency = library()
        val application = application("package external; public class App extends Base {}", dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertTrue("external.Base.packageRead()Ljava/lang/String;" in declarations.inheritedMethods("external.App").orEmpty())
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        assertTrue(ResourceRead("messages", "package") in snapshot.methods.getValue("external.App.<init>()V").resourceReads)
    }

    @Test fun `default interface API is inherited but interface static API is not`() {
        val dependency = compile("default-library", mapOf("Feature.java" to """
            package external;
            public interface Feature {
                default String read() { return java.util.ResourceBundle.getBundle("messages").getString("default"); }
                static String unrelated() { return java.util.ResourceBundle.getBundle("messages").getString("interface-static"); }
            }
        """.trimIndent()))
        val application = application("public class App implements external.Feature {}", dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertEquals(setOf("external.Feature.read()Ljava/lang/String;"), declarations.inheritedMethods("App"))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        val reads = snapshot.methods.getValue("App.<init>()V").resourceReads
        assertTrue(ResourceRead("messages", "default") in reads)
        assertTrue(reads.none { it.key == "interface-static" })
    }

    @Test fun `inherited class implementation wins over interface defaults without reading hidden default`() {
        val dependency = compile("class-wins-library", mapOf("Feature.java" to """
            package external;
            public interface Feature {
                default String read() { return java.util.ResourceBundle.getBundle("messages").getString("hidden-default"); }
            }
        """.trimIndent(), "Base.java" to """
            package external;
            public class Base { public String read() { return "safe"; } }
        """.trimIndent()))
        val application = application("public class App extends external.Base implements external.Feature {}", dependency)
        val declarations = ResourceDeclarations(listOf(application, dependency))
        assertEquals(setOf("external.Base.read()Ljava/lang/String;"), declarations.inheritedMethods("App"))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(dependency), dependencyResources = true)
        assertTrue(snapshot.methods.values.all { it.resourceReads.isEmpty() }, snapshot.methods.toString())
    }

    private fun library(): Path = compile("library", mapOf("Base.java" to """
        package external;
        public class Base {
            public String inheritedRead() { return java.util.ResourceBundle.getBundle("messages").getString("inherited"); }
            protected String protectedRead() { return java.util.ResourceBundle.getBundle("messages").getString("protected"); }
            public static String staticRead() { return java.util.ResourceBundle.getBundle("messages").getString("static"); }
            private String hiddenRead() { return java.util.ResourceBundle.getBundle("messages").getString("private"); }
            String packageRead() { return java.util.ResourceBundle.getBundle("messages").getString("package"); }
        }
    """.trimIndent(), "Unrelated.java" to """
        package external;
        public class Unrelated {
            public String read() { return java.util.ResourceBundle.getBundle("messages").getString("unrelated"); }
        }
    """.trimIndent()))

    private fun application(source: String, dependency: Path): Path = compile("application", mapOf("App.java" to source), dependency)

    private fun compile(name: String, sources: Map<String, String>, dependency: Path? = null): Path {
        val root = Files.createDirectories(directory.resolve(name))
        val classes = Files.createDirectories(root.resolve("classes"))
        val files = sources.map { (file, source) -> root.resolve(file).also { Files.writeString(it, source) }.toString() }
        val arguments = listOf("-d", classes.toString()) +
            (dependency?.let { listOf("-cp", it.toString()) }.orEmpty()) + files
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return classes
    }
}
