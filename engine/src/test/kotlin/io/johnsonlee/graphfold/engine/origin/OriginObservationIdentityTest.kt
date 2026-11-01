package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginObservationIdentityTest {
    @TempDir lateinit var temporary: Path

    @Test fun `typed observer identities and inherited stack access match real normalized folded method calls`() {
        val source = temporary.resolve("Host.java")
        Files.writeString(source, """
            public class Host {
                public static class Token {
                    public static Token[][] echo(Token[][] values) {
                        System.out.println(String.valueOf(values));
                        return values;
                    }
                    public String toString() { return "token"; }
                }
                public static String render(Token value) { return value.toString(); }
                public static String inherited(RuntimeException failure) {
                    return failure.getStackTrace()[0].getClassName();
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val normalized = normalizeOriginClasses(classes, temporary.resolve("normalized"),
            mapOf("Host\$Token" to "Host\$Stable"))
        assertTrue(normalized.diagnostics.isEmpty(), normalized.diagnostics.toString())
        val echo = "Host\$Stable.echo([[LHost\$Stable;)[[LHost\$Stable;"
        val render = "Host.render(LHost\$Stable;)Ljava/lang/String;"
        val inherited = "Host.inherited(Ljava/lang/RuntimeException;)Ljava/lang/String;"
        assertTrue(echo in normalized.methodDiagnostics, normalized.methodDiagnostics.toString())
        assertTrue(normalized.methodDiagnostics.getValue(render).any { it.callee == "Host\$Stable.toString()Ljava/lang/String;" })
        assertTrue(normalized.methodDiagnostics.getValue(inherited).any {
            it.callee == "java.lang.RuntimeException.getStackTrace()[Ljava/lang/StackTraceElement;"
        })
        assertTrue(normalized.methodDiagnostics.values.flatten().all { it.observedOwners == setOf("Host\$Stable") })
        val project = OriginProject(CompiledProject(normalized.classes, listOf(temporary), emptyList()),
            classes, OriginCatalog(emptyList(), emptyList()), normalized.owners, normalized.methodDiagnostics)
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(project, emptyList())
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertEquals(normalized.methodDiagnostics, snapshot.methodDiagnostics)
        assertFalse(snapshot.methodDiagnostics.isEmpty())
        snapshot.methodDiagnostics.forEach { (identity, gaps) ->
            val calls = snapshot.methods.getValue(identity).calls
            gaps.forEach { gap -> assertTrue(gap.callee in calls, "$identity: ${gap.callee} missing from $calls") }
        }
    }
}
