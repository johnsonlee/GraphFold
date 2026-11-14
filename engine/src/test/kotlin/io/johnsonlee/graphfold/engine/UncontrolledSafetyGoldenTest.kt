package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.inputs.archive.ArchiveInput
import io.johnsonlee.graphfold.engine.inputs.archive.ArchiveEntryInput
import io.johnsonlee.graphfold.engine.inputs.archive.prepareArchiveInput
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.params.provider.MethodSource
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.archive.InputProject
import io.johnsonlee.graphfold.engine.inputs.archive.toStaticScope
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.diff.Edit
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real compiler output supplies both static properties and the independent relational proof. */
class UncontrolledSafetyGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("java,true", "java,false", "kotlin,true", "kotlin,false")
    fun `retained pure arithmetic is accepted without relabeling uncontrolled changes`(language: String, mapped: Boolean) {
        val before = revision("base", language, body(language, false), provenance = mapped)
        val after = revision("head", language, body(language, true), provenance = mapped)
        assertTrue(hasAddition(after), "Compiler must retain the additional arithmetic for this proof case")
        assertFalse(hasAddition(before))
        val result = analyze(before, after, language, mapped)
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty() && result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.ruleAudits.isEmpty(), "An empty control set must not invent control selection")
        val reasons = result.reasons.filter { it.code == ReasonCode.STATIC_SAFETY_PROVEN }
        assertTrue(reasons.isNotEmpty(), result.toString())
        reasons.forEach { reason ->
            assertEquals(ControlClassification.UNCONTROLLED, reason.assessment.control)
            assertEquals(AcceptanceDecision.ACCEPTED, reason.assessment.decision)
            assertEquals(AcceptanceBasis.STATIC_SAFETY, reason.assessment.basis)
            assertTrue(reason.assessment.safety.any { it.claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT &&
                it.status == SafetyStatus.PROVEN && it.evidence.isNotEmpty() }, reason.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["java", "kotlin"])
    fun `return price changed to zero fails despite all three local safety properties`(language: String) {
        val before = revision("base", language, body(language, false))
        val after = revision("head", language, body(language, false).replace("return price", "return 0"))
        val result = analyze(before, after, language, true)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        val evidence = result.reasons.flatMap { it.assessment.safety }
        listOf(SafetyClaim.EFFECT_FREE, SafetyClaim.NONTHROWING, SafetyClaim.TERMINATING).forEach { claim ->
            val properties = evidence.filter { it.claim == claim }
            assertTrue(properties.any { it.subject.startsWith("BASE ") }, result.toString())
            assertTrue(properties.any { it.subject.startsWith("HEAD ") }, result.toString())
            assertTrue(properties.all { it.status == SafetyStatus.PROVEN }, result.toString())
        }
        assertTrue(evidence.any { it.claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT &&
            it.status != SafetyStatus.PROVEN }, result.toString())
        assertTrue(result.reasons.none { it.assessment.control == ControlClassification.CONTROLLED })
    }

    @ParameterizedTest
    @CsvSource("dereference,String text,int ignored = text.length();",
        "argument,int price,int ignored = helper(1 / price);",
        "unboxing,Integer price,int ignored = price;",
        "literal-null,String text,String target = null; int ignored = target.length();",
        "unsupported,int price,double ignored = Math.sin(price);")
    fun `hazardous or unsupported discarded computations cannot use equal return values`(
        mode: String, parameter: String, computation: String,
    ) {
        val helper = "static int helper(int input) { return input; }"
        val base = "public class Example {\n public static int value($parameter) { return 1; }\n $helper\n}"
        val head = base.replace("return 1;", "$computation return 1;")
        val result = analyze(revision("base-$mode", "java", base), revision("head-$mode", "java", head), "java", true)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.reasons.none { it.code == ReasonCode.STATIC_SAFETY_PROVEN }, result.toString())
        val expected = if (mode == "literal-null") SafetyStatus.REFUTED else SafetyStatus.UNPROVEN
        assertTrue(result.reasons.flatMap { it.assessment.safety }.any {
            it.claim == SafetyClaim.NONTHROWING && it.status == expected && it.subject.startsWith("HEAD ")
        }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["class", "field", "metadata", "resource"])
    fun `safe arithmetic does not discharge unrelated whole input differences`(mode: String) {
        val base = body("java", false)
        val changed = when (mode) {
            "class" -> body("java", true) + "\nclass Additional {}"
            "field" -> body("java", true).replace("public class Example {", "public class Example { public int added;")
            "metadata" -> body("java", true).replace("public static int", "@Deprecated public static int")
            else -> body("java", true)
        }
        val before = revision("base", "java", base, resource = "before")
        val after = revision("head", "java", changed, resource = if (mode == "resource") "after" else "before")
        assertTrue(hasAddition(after))
        val result = analyze(before, after, "java", false)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.blocking } || result.findings.isNotEmpty(), result.toString())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("packagedCases")
    fun `packaged changes satisfy the explicit independent safety contract`(case: SafetyGoldenCase) {
        val before = revision("base", case.language, packagedSource(case, false), provenance = false)
        val after = revision("head", case.language, packagedSource(case, true), provenance = false)
        val identities = listOf(before, after).map { revision ->
            applicationClasses(revision).flatMap { node ->
                node.methods.filter { it.name == "value" }
                    .map { "${node.name.replace('/', '.')}.${it.name}${it.desc}" }
            }.single()
        }
        assertEquals(identities.first(), identities.last(), "$case: the changed obligation must retain its identity")
        val beforeInstructions = applicationClasses(before).flatMap { it.methods }.filter { it.name == "value" }
            .flatMap { it.instructions.toList() }.map { it.opcode }.filter { it >= 0 }
        val afterInstructions = applicationClasses(after).flatMap { it.methods }.filter { it.name == "value" }
            .flatMap { it.instructions.toList() }.map { it.opcode }.filter { it >= 0 }
        assertTrue(beforeInstructions != afterInstructions, "$case: compiler must retain changed executable instructions")
        if (case.change.startsWith("helper-")) {
            assertFalse(hasHelperCall(before), "BASE must not already call the helper")
            assertTrue(hasHelperCall(after), "The actual compiler must retain HEAD's helper invocation")
        }
        val result = analyze(packaged(before, case.inputVariant), packaged(after, case.inputVariant), case.language, false)
        assertEquals(case.expected.verdict, result.verdict, result.toString())
        assertTrue(result.ruleAudits.isEmpty(), result.toString())
        assertTrue(result.reasons.isNotEmpty(), result.toString())
        assertEquals(case.expected.diagnosticCodes, result.diagnostics.map { it.code }, result.toString())
        if (case.change in setOf("helper-opaque", "opaque-alternative")) {
            val nativeIdentity = applicationClasses(after).flatMap { node ->
                node.methods.filter { it.access and Opcodes.ACC_NATIVE != 0 }
                    .map { "${node.name.replace('/', '.')}.${it.name}${it.desc}" }
            }.single()
            val inventoryGap = "Native/JNI method prevents proving private callback reachability: $nativeIdentity"
            val expectedMessages = listOf("base: $inventoryGap", "head: $inventoryGap",
                "Graphite NATIVE: $nativeIdentity", "Graphite observed NATIVE: $nativeIdentity")
            assertEquals(expectedMessages, result.diagnostics.take(4).map { it.message }, case.id)
        }
        assertTrue(result.diagnostics.all { it.blocking }, result.toString())
        assertTrue(result.findings.isEmpty(), "Source-free packaged inputs must not invent source findings")
        if (case.expected.decision == AcceptanceDecision.ACCEPTED) {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertTrue(result.metadata.obligations > 0, result.toString())
            assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        }
        result.reasons.forEach { reason -> assertContract(case, identities.first(), reason.assessment) }
    }

    private fun packagedSource(case: SafetyGoldenCase, changed: Boolean): String {
        case.sourcePair?.let { pair ->
            val resource = if (changed) pair.headResource else pair.baseResource
            return requireNotNull(javaClass.getResource(resource)).readText()
        }
        return if (case.change.startsWith("helper-")) helperSource(case, changed) else when {
            !changed -> body(case.language, false)
            case.change == "safe" -> body(case.language, true)
            case.change == "return-change" -> body(case.language, false).replace("return price", "return 0")
            case.language == "java" -> body("java", false)
                .replace("return price;", "String target = null; int ignored = target.length(); return price;")
            else -> body("kotlin", false)
                .replace("return price", "val target: String? = null; val ignored = target!!.length; return price")
        }
    }

    private fun helperSource(case: SafetyGoldenCase, changed: Boolean): String {
        val result = if (changed) "helper(price)" else "price"
        return if (case.language == "java") {
            val helper = when (case.change) {
                "helper-pure" -> "private static int helper(int price) { return price + 0; }"
                "helper-effect" -> "private static int count; private static int helper(int price) { count++; return price; }"
                else -> "private static native int helper(int price);"
            }
            "public class Example {\n public static int value(int price) { return $result; }\n $helper\n}"
        } else {
            val helper = when (case.change) {
                "helper-pure" -> "private fun helper(price: Int): Int = price + 0"
                "helper-effect" -> "private var count: Int = 0\nprivate fun helper(price: Int): Int { count++; return price }"
                else -> "private external fun helper(price: Int): Int"
            }
            "fun value(price: Int): Int = $result\n$helper\n"
        }
    }

    private fun hasHelperCall(revision: PreparedRevision): Boolean = applicationClasses(revision).any { node ->
        node.methods.filter { it.name == "value" }.flatMap { it.instructions.toList() }
            .filterIsInstance<MethodInsnNode>().any { it.name == "helper" }
    }

    private fun assertContract(case: SafetyGoldenCase, identity: String, assessment: ChangeAssessment) {
        assertEquals(case.expected.control, assessment.control, case.id)
        assertEquals(case.expected.decision, assessment.decision, case.id)
        assertEquals(case.expected.basis, assessment.basis, case.id)
        val expectedProofs = case.expected.claims.flatMap { (claim, status) ->
            if (claim == SafetyClaim.WHOLE_CHANGE_EQUIVALENT) listOf(Triple(claim, identity, status))
            else listOf(Triple(claim, "BASE $identity", SafetyStatus.PROVEN), Triple(claim, "HEAD $identity", status))
        }
        val actualProofs = assessment.safety.map { Triple(it.claim, it.subject, it.status) }
        assertEquals(7, expectedProofs.size, case.id)
        assertEquals(expectedProofs.size, actualProofs.size, case.id)
        assertEquals(expectedProofs.toSet(), actualProofs.toSet(), case.id)
        assertEquals(setOf("NONEMPTY_SUBJECT", "NONEMPTY_CITATIONS", "NONEMPTY_ASSUMPTIONS"),
            case.evidenceRequirements.toSet())
        assessment.safety.forEach { proof ->
            assertTrue(proof.subject.isNotBlank(), case.id)
            assertTrue(proof.evidence.isNotEmpty() && proof.evidence.all(String::isNotBlank), case.id)
            assertTrue(proof.assumptions.isNotEmpty() && proof.assumptions.all(String::isNotBlank), case.id)
        }
    }

    private fun packaged(revision: PreparedRevision, variant: String): PreparedRevision {
        val scope = revision.scopes.getValue("application")
        val prefix = if (variant == "boot") "BOOT-INF" else "WEB-INF"
        val archive = temporary.resolve("${revision.sha}.${if (variant == "boot") "jar" else "war"}")
        val entries = Files.walk(scope.classes).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .associate { "$prefix/classes/${scope.classes.relativize(it)}" to Files.readAllBytes(it) }.toMutableMap()
        scope.dependencies.forEachIndexed { index, path -> entries["$prefix/lib/dependency-$index.jar"] = Files.readAllBytes(path) }
        entries["$prefix/classes/config.txt"] = "stable resource".toByteArray()
        if (variant == "war") entries["META-INF/context.xml"] = "<Context/>".toByteArray()
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            entries.toSortedMap().forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name)); jar.write(bytes); jar.closeEntry()
            }
        }
        val output = temporary.resolve("${revision.sha}-packaged")
        val emptyEvidence = InputProject(Files.createDirectories(output.resolve("empty-compiler")), emptyList(), emptyList())
        val input = prepareArchiveInput(ArchiveInput("selected", listOf(ArchiveEntryInput(archive))),
            emptyEvidence, output.resolve("prepared"), variantThreshold = 17)
        val normalized = input.toStaticScope(output.resolve("dependencies"), variantThreshold = 17)
        assertTrue(normalized.sourceRoots.isEmpty() && normalized.originDirectory == null)
        return PreparedRevision(revision.sha, temporary.resolve("missing-${revision.sha}"),
            mapOf("application" to normalized), input.production)
    }

    private fun analyze(base: PreparedRevision, head: PreparedRevision, language: String, mapped: Boolean): AnalysisResult {
        val path = sourcePath(language)
        val change = if (mapped) changedFile(path, path, edits = listOf(Edit(0, 3, 0, 3)))
            else changedFile("generator.txt", "generator.txt")
        return GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(if (mapped) base else withoutSourceEvidence(base),
                if (mapped) head else withoutSourceEvidence(head), listOf(change), "version: 1\nfolds: []"))
    }

    private fun withoutSourceEvidence(revision: PreparedRevision) = revision.copy(
        sourceDirectory = temporary.resolve("absent-${revision.sha}"),
        scopes = revision.scopes.mapValues { (_, scope) -> scope.copy(sourceRoots = emptyList(),
            originDirectory = null, originRunId = null, sourceOutputs = emptyMap()) },
    )

    private fun revision(
        name: String, language: String, source: String, resource: String? = null, provenance: Boolean = true,
    ): PreparedRevision {
        val checkout = Files.createDirectories(temporary.resolve(name).resolve("source"))
        val file = checkout.resolve(sourcePath(language))
        Files.createDirectories(file.parent)
        Files.writeString(file, source)
        val output = temporary.resolve(name).resolve("output")
        val compiled = if (language == "kotlin" && !provenance) compilePlainKotlin(file, output)
            else SourceFixtureCompiler(Duration.ofMinutes(1)).compile(checkout, output)
        val entries = Files.walk(compiled.classes).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .associate { "class/${compiled.classes.relativize(it)}" to digest(it) }
        val resources = resource?.let {
            mapOf("resource/data.txt" to digest(Files.writeString(temporary.resolve("$name-data.txt"), it)))
        }.orEmpty()
        val inventory = ContentInventory(entries + resources, resources)
        val scope = compiled.toStaticScope(output.resolve("normalized-dependencies"), variantThreshold = 17)
            .copy(production = inventory)
        return PreparedRevision(name, checkout, mapOf("application" to scope), inventory)
    }

    private fun compilePlainKotlin(file: Path, output: Path): InputProject {
        val classes = Files.createDirectories(output.resolve("classes"))
        val dependencies = listOf(Unit::class.java, org.jetbrains.annotations.NotNull::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }
        val messages = ByteArrayOutputStream()
        val status = PrintStream(messages).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-classpath", dependencies.joinToString(java.io.File.pathSeparator),
                "-d", classes.toString(), file.toString())
        }
        assertEquals(ExitCode.OK, status, messages.toString())
        return InputProject(classes, listOf(file.parent), dependencies)
    }

    private fun body(language: String, extra: Boolean): String = if (language == "java") {
        "public class Example {\n public static int value(int price) { " +
            (if (extra) "int ignored = price + 1; " else "") + "return price; }\n}"
    } else {
        "fun value(price: Int): Int {\n " + (if (extra) "val ignored = price + 1; " else "") + "return price\n}"
    }

    private fun sourcePath(language: String) = if (language == "java") "src/main/java/Example.java"
        else "src/main/kotlin/Example.kt"

    private fun hasAddition(revision: PreparedRevision): Boolean = applicationClasses(revision).any { node ->
        node.methods.any { method -> method.name == "value" && method.instructions.any { it.opcode == Opcodes.IADD } }
    }

    private fun applicationClasses(revision: PreparedRevision): List<ClassNode> {
        val classes = revision.scopes.getValue("application").classes
        return Files.walk(classes).use { paths -> paths.filter { it.toString().endsWith(".class") }.toList() }
            .map { path -> ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) } }
    }
    companion object {
        @JvmStatic
        fun packagedCases(): List<SafetyGoldenCase> = requireNotNull(
            UncontrolledSafetyGoldenTest::class.java.getResourceAsStream("/safety/manifest.json"),
        ).use { jacksonObjectMapper().readValue(it) }
    }

}

/** Golden expectations describe independent axes; proof citations must come from the engine. */
data class SafetyGoldenCase(
    val id: String,
    val language: String,
    val inputVariant: String,
    val change: String,
    val expected: SafetyGoldenExpectation,
    val evidenceRequirements: List<String>,
    val sourcePair: SafetyGoldenSourcePair? = null,
) {
    override fun toString(): String = id
}

/** Expected verdict does not substitute for either control classification or individual proof status. */
data class SafetyGoldenExpectation(
    val control: ControlClassification,
    val claims: Map<SafetyClaim, SafetyStatus>,
    val decision: AcceptanceDecision,
    val basis: AcceptanceBasis,
    val verdict: Verdict,
    val diagnosticCodes: List<DiagnosticCode>,
)

/** Actual compiler source fixtures, independently stored for each revision. */
data class SafetyGoldenSourcePair(val baseResource: String, val headResource: String)
