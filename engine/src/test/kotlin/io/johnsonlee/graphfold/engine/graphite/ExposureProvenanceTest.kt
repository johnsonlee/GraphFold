package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.sootup.FoldedMethodObservation
import io.johnsonlee.graphite.sootup.MethodObservationEvidence
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposureProvenanceTest {
    @TempDir lateinit var directory: Path

    @Test fun `a unique selected call needs a successful same rule receipt`() {
        val (provenance, sites) = capture("unique", "return x > 0 && enabled();")
        assertEquals(1, sites.size)
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, emptySet()).isEmpty(), "An argument-unmatched rule cannot authorize erasure")
        assertTrue(provenance.authorized(METHOD, setOf(1)).isEmpty(), "Another successful rule is insufficient")
        assertTrue(provenance.authorized(METHOD, setOf(0)).isNotEmpty())
    }

    @Test fun `absent or repeated direct calls cannot authorize branch erasure`() {
        val (absent, single) = capture("absent", "return x > 0 && enabled();")
        absent.select(0, emptyList())
        assertTrue(absent.authorized(METHOD, setOf(0)).isEmpty())
        assertEquals(1, single.size)
        val (repeated, sites) = capture("repeated", "return x > 0 && enabled() || x < 0 && enabled();")
        assertEquals(2, sites.size)
        repeated.select(0, sites)
        assertTrue(repeated.authorized(METHOD, setOf(0)).isEmpty())
        repeated.select(1, sites.take(1))
        assertTrue(repeated.authorized(METHOD, setOf(1)).isEmpty(), "One selected occurrence cannot map an ambiguous callee")
    }

    @Test fun `an unrelated branch that rejoins before AB does not control the call`() {
        val (provenance, sites) = capture("rejoin", "if (x > 0) x = 1; else x = 2; return enabled();")
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
    }

    @Test fun `duplicate predicate and source position do not identify a unique original branch`() {
        val (provenance, sites) = capture("ambiguous", "if (x > 0) { if (x > 0) return enabled(); } return false;")
        assertEquals(1, sites.size)
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
    }

    @Test fun `compound OR AND predicates retain their transitive link to the selected AB call`() {
        val (provenance, sites) = capture("compound", "return ((x > 0 && x < 10) || x < -10) && enabled();")
        assertEquals(1, sites.size)
        provenance.select(0, sites)
        assertEquals(3, provenance.authorized(METHOD, setOf(0)).size)
    }

    @Test fun `already redundant pre-fold conditional does not acquire AB authority`() {
        val (provenance, sites) = capture("alreadyRedundant", "if (x > 0) {} return enabled();")
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
    }

    @Test fun `derived and unidentified call sites cannot borrow direct bytecode authority`() {
        val (provenance, sites) = capture("siteEvidence", "return x > 0 && enabled();")
        val site = sites.single()
        val variants = listOf(site.copy(origin = site.id), site.copy(ordinal = null), site.copy(ordinal = -1))
        variants.forEachIndexed { rule, variant ->
            provenance.select(rule, listOf(variant))
            assertTrue(provenance.authorized(METHOD, setOf(rule)).isEmpty())
        }
        val absent = ExposureProvenance()
        absent.select(0, sites)
        assertTrue(absent.authorized(METHOD, setOf(0)).isEmpty(), "Selected caller needs a captured pre-fold body")
    }

    @Test fun `dependency or unknown ownership and absent debug coordinates provide no authority`() {
        for (application in listOf(false, null)) {
            val (provenance, sites) = capture("ownership${application?.toString() ?: "unknown"}", "return x > 0 && enabled();", application)
            provenance.select(0, sites)
            assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
        }
        val (provenance, sites) = capture("noDebug", "return x > 0 && enabled();", debug = false)
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
    }

    @Test fun `a selected call without an observed body cannot authorize exposure elimination`() {
        val (provenance, sites) = capture("noBody", "return x > 0 && enabled();", bodyAvailable = false)
        assertEquals(1, sites.size)
        provenance.select(0, sites)
        assertTrue(provenance.authorized(METHOD, setOf(0)).isEmpty())
    }

    private fun capture(
        name: String, body: String, application: Boolean? = true, debug: Boolean = true, bodyAvailable: Boolean = true,
    ): Pair<ExposureProvenance, List<CallSiteNode>> {
        val root = Files.createDirectories(directory.resolve(name))
        val source = root.resolve("Exposure.java")
        Files.writeString(source, """
            public class Exposure {
                static boolean flag;
                private static native boolean opaque();
                static boolean enabled() { return flag; }
                public static boolean quote(int x) { $body }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            if (debug) "-g" else "-g:none", "-d", classes.toString(), source.toString()))
        val provenance = ExposureProvenance()
        val capturedOwnership = application
        val observed = JavaProjectLoader(LoaderConfig(), false).loadObserved(classes) { observation ->
            provenance.capture(object : FoldedMethodObservation by observation {
                override val snapshot = observation.snapshot.takeIf { bodyAvailable }
                override val evidence = object : MethodObservationEvidence by observation.evidence {
                    override val application: Boolean? = capturedOwnership
                }
            })
        }
        val sites = observed.graph.nodes(CallSiteNode::class.java).filter {
            it.caller.identity == METHOD && it.callee.identity == "Exposure.enabled()Z"
        }
        return provenance to sites.toList()
    }

    companion object {
        private const val METHOD = "Exposure.quote(I)Z"
    }
}
