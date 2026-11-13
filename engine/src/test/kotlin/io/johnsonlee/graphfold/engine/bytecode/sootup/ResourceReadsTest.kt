package io.johnsonlee.graphfold.engine.bytecode.sootup

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphfold.engine.inputs.archive.toStaticScope
import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResourceReadsTest {
    @TempDir lateinit var directory: Path

    @Test fun `discarded bundle results and static cache operations retain unknown effects`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static void discard() { java.util.ResourceBundle.getBundle("messages"); }
            public static void clear() { java.util.ResourceBundle.clearCache(); }
        """.trimIndent()), emptyList())
        assertEquals(listOf(ResourceRead(null, null)), snapshot.methods.getValue("Sample.discard()V").resourceReads)
        assertEquals(listOf(ResourceRead(null, null)), snapshot.methods.getValue("Sample.clear()V").resourceReads)
    }

    @Test fun `folded keys disappear while ordinary bundle reads remain`() {
        val source = """
            public static String run() {
                java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("i18n.messages");
                if (enabled()) return bundle.getString("new");
                return bundle.getString("old");
            }
        """.trimIndent()
        val classes = compile(source)
        val plain = GraphiteAnalyzer().analyze(classes, emptyList()).methods.getValue("Sample.run()Ljava/lang/String;")
        assertEquals(setOf(ResourceRead("i18n/messages", "old"), ResourceRead("i18n/messages", "new")), plain.resourceReads.toSet())
        val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf("callee_class" to "Sample", "callee_name" to "enabled")),
            "value" to false))
        val folded = GraphiteAnalyzer().analyze(classes, listOf(fold)).methods.getValue("Sample.run()Ljava/lang/String;")
        assertEquals(listOf(ResourceRead("i18n/messages", "old")), folded.resourceReads)
    }

    @Test fun `dynamic bundle key locale overload external bundle and escapes retain unknown reads`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static String dynamic(String name, String key) { return java.util.ResourceBundle.getBundle(name).getString(key); }
            public static Object escape() { return java.util.ResourceBundle.getBundle("messages"); }
            public static String locale() { return java.util.ResourceBundle.getBundle("messages", java.util.Locale.US).getString("x"); }
            public static String external(java.util.ResourceBundle b) { return b.getString("x"); }
            public static Object keys() { return java.util.ResourceBundle.getBundle("messages").keySet(); }
            public static Object raw() { return Sample.class.getResourceAsStream("/messages.properties"); }
            public static Object properties() { return new java.util.Properties(); }
            public static Object files() { return new java.io.File("messages.properties"); }
            public static Object nio() { return java.nio.file.Path.of("messages.properties"); }
            public static Object objects() { return java.util.ResourceBundle.getBundle("messages").getObject("object"); }
            public static Object array() { return java.util.ResourceBundle.getBundle("messages").getStringArray("array"); }
            public static boolean contains() { return java.util.ResourceBundle.getBundle("messages").containsKey("exists"); }
        """.trimIndent()), emptyList())
        fun reads(name: String) = snapshot.methods.values.single { it.identity.substringBefore('(') == "Sample.$name" }.resourceReads
        assertTrue(ResourceRead(null, null) in reads("dynamic"))
        assertEquals(listOf(ResourceRead("messages", null)), reads("escape"))
        assertTrue(ResourceRead(null, null) in reads("locale"))
        assertEquals(listOf(ResourceRead(null, "x")), reads("external"))
        assertEquals(listOf(ResourceRead("messages", null)), reads("keys"))
        listOf("raw", "properties", "files", "nio").forEach { assertTrue(ResourceRead(null, null) in reads(it), it) }
        listOf("objects" to "object", "array" to "array", "contains" to "exists").forEach { (method, key) ->
            assertEquals(listOf(ResourceRead("messages", key)), reads(method))
        }
    }

    @Test fun `reassigned and aliased bundles conservatively retain all possible resources`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static String aliases(boolean choice) {
                java.util.ResourceBundle bundle;
                if (choice) bundle = java.util.ResourceBundle.getBundle("one");
                else bundle = java.util.ResourceBundle.getBundle("two");
                return bundle.getString("key");
            }
            public static void passed() { consume(java.util.ResourceBundle.getBundle("messages")); }
            private static void consume(Object value) {}
        """.trimIndent()), emptyList())
        assertEquals(setOf(ResourceRead(null, "key"), ResourceRead(null, null)),
            snapshot.methods.getValue("Sample.aliases(Z)Ljava/lang/String;").resourceReads.toSet())
        assertEquals(listOf(ResourceRead("messages", null)), snapshot.methods.getValue("Sample.passed()V").resourceReads)
    }

    @Test fun `unmodeled platform consumers and escaped stored bundles are wildcard reads`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static java.util.ResourceBundle saved;
            public static Object scanner(java.nio.file.Path path) throws Exception { return new java.util.Scanner(path); }
            public static Object url(java.net.URL url) throws Exception { return url.openStream(); }
            public static Object connection(java.net.URLConnection value) throws Exception { return value.getInputStream(); }
            public static Object platform(javax.xml.parsers.DocumentBuilder parser) throws Exception {
                return parser.parse("messages.properties");
            }
            public static void stored() { saved = java.util.ResourceBundle.getBundle("messages"); }
            public static Object noBody() { return null; }
        """.trimIndent()), emptyList())
        listOf("scanner", "url", "connection", "platform").forEach { name ->
            val method = snapshot.methods.values.single { it.identity.substringBefore('(') == "Sample.$name" }
            assertTrue(ResourceRead(null, null) in method.resourceReads, "$name: ${method.resourceReads}")
        }
        val stored = snapshot.methods.getValue("Sample.stored()V").resourceReads
        assertTrue(stored.any { it.key == null }, stored.toString())
        assertTrue(snapshot.methods.getValue("Sample.noBody()Ljava/lang/Object;").resourceReads.isEmpty())
    }

    @Test fun `Kotlin bundle readers have the same folded key evidence as Java`() {
        val root = Files.createDirectories(directory.resolve("kotlin"))
        val source = Files.createDirectories(root.resolve("src/main/kotlin")).resolve("Sample.kt")
        Files.writeString(source, """
            class Sample {
                fun enabled(): Boolean = true
                fun run(): String {
                    val bundle = java.util.ResourceBundle.getBundle("messages")
                    if (enabled()) return bundle.getString("new")
                    return bundle.getString("old")
                }
            }
        """.trimIndent())
        val compiled = io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler(java.time.Duration.ofMinutes(1))
            .compile(root, directory.resolve("output"))
            .toStaticScope(directory.resolve("prepared"))
        val fold = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf("callee_class" to "Sample", "callee_name" to "enabled")),
            "value" to false))
        val folded = GraphiteAnalyzer().analyze(compiled.classes, listOf(fold), compiled.dependencies)
        assertEquals(listOf(ResourceRead("messages", "old")), folded.methods.getValue("Sample.run()Ljava/lang/String;").resourceReads)
    }

    @Test fun `Java scalar output concatenation and conversions do not invent resource reads`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static String values(int value) {
                System.out.println("hello");
                System.err.print(value);
                System.out.println();
                Integer boxed = Integer.valueOf(value);
                System.out.println(boxed);
                int parsed = Integer.parseInt("42");
                String text = new StringBuilder().append("count=").append(parsed).append(true).toString();
                String buffer = new StringBuffer("text").append(value).toString();
                return text + buffer + boxed.intValue();
            }
            public static String conversions() {
                return String.valueOf(1) + Boolean.parseBoolean("true") + Long.parseLong("ff", 16)
                    + Double.valueOf("2.0").doubleValue() + Float.toString(3.0f);
            }
        """.trimIndent()), emptyList())
        listOf("values", "conversions").forEach { name ->
            val method = snapshot.methods.values.single { it.identity.substringBefore('(') == "Sample.$name" }
            assertTrue(method.resourceReads.isEmpty(), "$name: ${method.resourceReads}")
        }
    }

    @Test fun `custom output object conversion property lookups and formatters remain unknown readers`() {
        val snapshot = GraphiteAnalyzer().analyze(compile("""
            public static void custom(java.io.PrintStream out) { out.println("hello"); }
            public static void object(Object value) { System.out.println(value); }
            public static String builder(Object value) { return new StringBuilder().append(value).toString(); }
            public static String sequence(CharSequence value) { return new StringBuffer().append(value).toString(); }
            public static String concatenation(Object value) { return "value=" + value; }
            public static Object property() { return Integer.getInteger("resource.path"); }
            public static void format(Object value) { System.out.printf("%s", value); }
            public static void replace(java.io.PrintStream out) { System.setOut(out); }
        """.trimIndent()), emptyList())
        listOf("custom", "object", "builder", "sequence", "concatenation", "property", "format", "replace").forEach { name ->
            val method = snapshot.methods.values.single { it.identity.substringBefore('(') == "Sample.$name" }
            assertTrue(ResourceRead(null, null) in method.resourceReads, "$name: ${method.resourceReads}")
        }
    }

    @Test fun `Kotlin scalar output and templates match Java while object templates retain unknown readers`() {
        val root = Files.createDirectories(directory.resolve("kotlin-output"))
        val source = Files.createDirectories(root.resolve("src/main/kotlin")).resolve("Sample.kt")
        Files.writeString(source, """
            class Sample {
                fun values(value: Int): String {
                    println("hello")
                    println(value)
                    val parsed = java.lang.Integer.parseInt("42")
                    return "value=${'$'}value parsed=${'$'}parsed"
                }
                fun unknown(value: Any): String {
                    println(value)
                    return "value=${'$'}value"
                }
                fun loader(): java.io.InputStream? = javaClass.getResourceAsStream("/messages.properties")
            }
        """.trimIndent())
        val compiled = io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler(java.time.Duration.ofMinutes(1))
            .compile(root, directory.resolve("kotlin-output-classes"))
            .toStaticScope(directory.resolve("prepared"))
        val snapshot = GraphiteAnalyzer().analyze(compiled.classes, emptyList(), compiled.dependencies)
        assertTrue(snapshot.methods.getValue("Sample.values(I)Ljava/lang/String;").resourceReads.isEmpty())
        listOf("unknown", "loader").forEach { name ->
            val method = snapshot.methods.values.single { it.identity.substringBefore('(') == "Sample.$name" }
            assertTrue(ResourceRead(null, null) in method.resourceReads, "$name: ${method.resourceReads}")
        }
    }

    private fun compile(body: String): Path {
        val source = directory.resolve("Sample.java")
        Files.writeString(source, "public class Sample { static boolean enabled() { return true; } $body }")
        val output = Files.createDirectories(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", output.toString(), source.toString()))
        return output
    }
}
