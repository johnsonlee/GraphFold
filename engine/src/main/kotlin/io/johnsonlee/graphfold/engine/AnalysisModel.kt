package io.johnsonlee.graphfold.engine

import java.nio.file.Path
import java.time.Duration

/** Default deadline for each external build invocation; it does not bound the entire analysis. */
val DEFAULT_BUILD_TIMEOUT: Duration = Duration.ofMinutes(10)
private const val DEFAULT_MAXIMUM_SCENARIOS = 32
/** Maximum concurrent analysis leases per repository cache unless configured otherwise. */
const val DEFAULT_MAXIMUM_WORKSPACE_SLOTS = 2
private const val REVISIONS_PER_ANALYSIS = 2L
private const val WORKSPACE_PREPARATION_MARGIN_MINUTES = 5L

/**
 * Computes a workspace acquisition budget from two build timeouts plus five minutes of preparation.
 *
 * @param buildTimeout Timeout for one external build invocation.
 * @return Waiting budget for an available workspace slot, not a total analysis deadline.
 * @throws ArithmeticException If the derived duration overflows.
 */
fun defaultWorkspaceLeaseTimeout(buildTimeout: Duration = DEFAULT_BUILD_TIMEOUT): Duration =
    buildTimeout.multipliedBy(REVISIONS_PER_ANALYSIS).plusMinutes(WORKSPACE_PREPARATION_MARGIN_MINUTES)

/**
 * Business inputs for comparing two Git revisions under explicit folding assumptions.
 *
 * @property repository Local repository path or remote Git URL.
 * @property base Base revision to resolve to a commit.
 * @property head Head revision to resolve to a commit.
 * @property fold YAML document contents, not a filename; may contain multiple folding scenarios.
 * @throws IllegalArgumentException If any input is blank.
 */
data class AnalysisRequest(
    val repository: String,
    val base: String,
    val head: String,
    val fold: String,
) {
    init {
        require(repository.isNotBlank()) { "repository must not be blank" }
        require(base.isNotBlank()) { "base must not be blank" }
        require(head.isNotBlank()) { "head must not be blank" }
        require(fold.isNotBlank()) { "fold must not be blank" }
    }
}

/** Gate decision derived from findings, diagnostics, and proof completion. */
enum class Verdict {
    /** All obligations are proved, with no findings or blocking diagnostics. */
    PASS,
    /** A finding, blocking diagnostic, or unproved obligation prevents passing. */
    FAIL,
}
/** Side of the Git comparison to which a source location or audit belongs. */
enum class Revision {
    /** The comparison baseline. */
    BASE,
    /** The proposed revision being checked. */
    HEAD,
}
/** Whether a finding identifies an uncontrolled change or an unresolved proof. */
enum class FindingKind {
    /** A change is identified as outside the specified folding control. */
    UNCONTROLLED_CHANGE,
    /** The analysis cannot prove a changed location safe under the folding assumptions. */
    UNVERIFIABLE_CHANGE,
}
/** Analysis phase associated with a diagnostic. */
enum class AnalysisStage {
    /** Request and fold-document validation. */
    INPUT,
    /** Revision resolution and workspace preparation. */
    GIT,
    /** Compilation and build-output collection. */
    BUILD,
    /** Source indexing and binding diff changes to proof obligations. */
    SOURCE,
    /** Graph construction and snapshot collection. */
    GRAPH,
    /** Application and validation of folding rules. */
    FOLD,
    /** Comparison of folded representations. */
    COMPARE,
    /** Report construction or encoding. */
    REPORT,
}

/**
 * One axis of a source span; combined with the other axis by [SourceLocation].
 *
 * @property start One-based coordinate of the first position.
 * @property end One-based coordinate of the exclusive end position. For columns across different lines,
 *   this may be less than [start].
 * @throws IllegalArgumentException If either coordinate is not positive.
 */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start > 0 && end > 0) { "coordinates must be positive" }
    }
}

/**
 * Repository-relative source span from `(line.start, column.start)` to the exclusive end position.
 * Columns count UTF-16 code units from one within their respective lines, not display widths.
 *
 * @property revision Revision containing this source span.
 * @property path Nonblank repository-relative path that does not escape the repository after normalization.
 * @property line One-based start and end line coordinates.
 * @property column One-based start and end column coordinates on their respective lines.
 * @throws IllegalArgumentException If the path escapes the repository or the end precedes the start.
 */
data class SourceLocation(
    val revision: Revision,
    val path: String,
    val line: SourceRange,
    val column: SourceRange,
) {
    init {
        require(path.isNotBlank() && !Path.of(path).isAbsolute) { "path must be repository-relative" }
        require(Path.of(path).normalize().none { it.toString() == ".." }) { "path must stay in repository" }
        require(line.end >= line.start) { "end line precedes start line" }
        require(line.end != line.start || column.end >= column.start) { "end column precedes start column" }
    }
}

/**
 * Supporting explanation for a finding, optionally anchored to source.
 *
 * @property description Human-readable evidence or remaining representation difference.
 * @property location Supporting source span, or null when the evidence has no precise source location.
 */
data class Evidence(val description: String, val location: SourceLocation? = null)

/**
 * A blocking finding anchored to verified locations from the base/head diff.
 *
 * @property id Identifier used to distinguish this finding in a report.
 * @property kind Classification of the failed proof.
 * @property ruleId Analysis rule responsible for reporting the finding.
 * @property message Human-readable explanation of the violation or unresolved proof.
 * @property locations Nonempty list of verified diff spans; may include both revisions.
 * @property evidence Additional explanations supporting the finding.
 * @property scenarios Names of the folding scenarios in which the finding occurred.
 * @throws IllegalArgumentException If identity or message fields are blank, or [locations] is empty.
 */
data class Finding(
    val id: String,
    val kind: FindingKind,
    val ruleId: String,
    val message: String,
    val locations: List<SourceLocation>,
    val evidence: List<Evidence> = emptyList(),
    val scenarios: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && ruleId.isNotBlank() && message.isNotBlank()) { "finding identity and message required" }
        require(locations.isNotEmpty()) { "findings require a verified diff location" }
    }
}

/** Stable machine-readable diagnostic identities; JSON uses these enum names unchanged. */
enum class DiagnosticCode {
    /** No workspace slot became available within the acquisition timeout. */
    WORKSPACE_CAPACITY_TIMEOUT,
    /** The analysis thread was interrupted. */
    ANALYSIS_CANCELLED,
    /** An unexpected analysis or adapter exception prevented completion. */
    ANALYSIS_FAILED,
    /** Compiler provenance could not be verified or aligned. */
    UNVERIFIED_ORIGIN,
    /** Graph construction or snapshot analysis reported a coverage gap. */
    INCOMPLETE_GRAPH,
    /** A fold rule matched no calls in either revision. */
    UNMATCHED_RULE,
    /** A changed input could not be mapped to supported source obligations. */
    UNMAPPED_CHANGE,
    /** Source indexing could not resolve a required declaration or symbol. */
    SOURCE_RESOLUTION_FAILED,
    /** A file move changed the JVM representation without a diff range for a finding. */
    UNLOCATED_MOVE_DIFFERENCE,
}

/**
 * Analysis condition that may prevent a reliable verdict without identifying a diff span.
 *
 * @property stage Phase that detected the condition.
 * @property code Stable machine-readable diagnostic identity.
 * @property message Human-readable explanation of the condition.
 * @property blocking Whether this diagnostic forces [Verdict.FAIL], even without findings.
 */
data class Diagnostic(
    val stage: AnalysisStage,
    val code: DiagnosticCode,
    val message: String,
    val blocking: Boolean = true,
)

/**
 * Revision identities and proof progress recorded for an analysis.
 *
 * @property baseSha Resolved base commit SHA, or the requested revision if resolution did not complete.
 * @property headSha Resolved head commit SHA, or the requested revision if resolution did not complete.
 * @property scenarios Names of the parsed folding scenarios.
 * @property obligations Number of obligations requiring proof; may reflect pending changed files on early failure.
 * @property provedObligations Number of obligations proved across every analyzed scenario.
 * @property graphiteVersion Graphite version recorded by the analysis.
 */
data class AnalysisMetadata(
    val baseSha: String,
    val headSha: String,
    val scenarios: List<String>,
    val obligations: Int,
    val provedObligations: Int,
    val graphiteVersion: String = "2.12.0",
)

/**
 * Complete or partial analysis report with a verdict derived from the available proof evidence.
 *
 * @property findings Violations anchored to changed source locations.
 * @property diagnostics Analysis gaps and other conditions, including failures without source locations.
 * @property metadata Revision identities and proof completion counts.
 * @property reasons Per-obligation explanations for individual folding scenarios.
 * @property ruleAudits Selection and matching records for each scenario, revision, and rule.
 */
data class AnalysisResult(
    val findings: List<Finding>,
    val diagnostics: List<Diagnostic>,
    val metadata: AnalysisMetadata,
    val reasons: List<VerdictReason> = emptyList(),
    val ruleAudits: List<RuleAudit> = emptyList(),
) {
    /** Version of the serialized report contract described by [AnalysisReports.schema]. */
    val schemaVersion: Int = 1
    /** Passes only with no findings, no blocking diagnostics, and all obligations proved. */
    val verdict: Verdict
        get() = if (findings.isEmpty() && diagnostics.none { it.blocking } &&
            metadata.obligations == metadata.provedObligations
        ) Verdict.PASS else Verdict.FAIL
}

/**
 * Resource limits and persistent cache settings shared by engine invocations.
 * Analyzed builds execute repository code and must be trusted or isolated by the caller.
 *
 * @property cacheDirectory Writable root for persistent Git workspaces and temporary analysis artifacts.
 * @property buildTimeout Positive timeout for each external build invocation, not the entire analysis.
 * @property maximumScenarios Positive upper bound on fold scenarios accepted in one request.
 * @property buildEnvironmentAllowlist Environment variable names external builds may inherit; not a sandbox.
 * @property maximumWorkspaceSlots Positive maximum number of concurrent workspace leases per repository cache.
 * @property workspaceLeaseTimeout Positive time budget for acquiring a free slot, not a checkout lease lifetime.
 * @throws IllegalArgumentException If any timeout or numerical limit is not positive.
 */
data class EngineConfiguration(
    val cacheDirectory: Path,
    val buildTimeout: Duration = DEFAULT_BUILD_TIMEOUT,
    val maximumScenarios: Int = DEFAULT_MAXIMUM_SCENARIOS,
    val buildEnvironmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
    val maximumWorkspaceSlots: Int = DEFAULT_MAXIMUM_WORKSPACE_SLOTS,
    val workspaceLeaseTimeout: Duration = defaultWorkspaceLeaseTimeout(buildTimeout),
) {
    init {
        require(!buildTimeout.isNegative && !buildTimeout.isZero) { "build timeout must be positive" }
        require(maximumScenarios > 0) { "maximumScenarios must be positive" }
        require(maximumWorkspaceSlots > 0) { "maximumWorkspaceSlots must be positive" }
        require(!workspaceLeaseTimeout.isNegative && !workspaceLeaseTimeout.isZero) {
            "workspace lease timeout must be positive"
        }
    }
}


/** Syntax-level categories describe the change, independently of whether it is safe. */
enum class ChangeKind {
    /** A field declaration was added. */
    FIELD_ADDED,
    /** A field declaration was removed. */
    FIELD_REMOVED,
    /** A field initializer changed. */
    FIELD_INITIALIZER_CHANGED,
    /** A field was paired with a declaration using a different name. */
    FIELD_RENAMED,
    /** The source classifier detected additional conditional syntax. */
    CONDITION_ADDED,
    /** The source classifier detected removed conditional syntax. */
    CONDITION_REMOVED,
    /** A condition changed without being classified solely as added or removed. */
    CONDITION_CHANGED,
    /** Invocation syntax changed. */
    CALL_CHANGED,
    /** A method declaration was added. */
    METHOD_ADDED,
    /** A method declaration was removed. */
    METHOD_REMOVED,
    /** A method was paired with a declaration using a different name. */
    METHOD_RENAMED,
    /** A type declaration was added. */
    TYPE_ADDED,
    /** A type declaration was removed. */
    TYPE_REMOVED,
    /** A declaration signature, modifier, or other declaration syntax changed. */
    DECLARATION_CHANGED,
    /** Executable body syntax changed. */
    BODY_CHANGED,
    /** The classifier found no relevant declaration or executable syntax change. */
    SOURCE_ONLY,
    /** The repository path changed while retaining the filename. */
    FILE_MOVED,
    /** The repository filename changed, possibly together with its directory. */
    FILE_RENAMED,
    /** Java static or Kotlin object/companion initialization syntax changed. */
    CLASS_INITIALIZATION_CHANGED,
}

/** Machine-readable outcome of a change proof under one folding scenario. */
enum class ReasonCode {
    /** No difference survives in the analyzed folded representation. */
    FOLDED_EQUIVALENT,
    /** The change is unreachable under the analyzed entry model and folding assumptions. */
    UNREACHABLE,
    /** The folded representations still differ for this obligation. */
    SURVIVING_DIFFERENCE,
    /** An analysis gap prevents a safety conclusion. */
    ANALYSIS_INCOMPLETE,
}

/**
 * Explanation of an obligation outcome under a particular folding scenario.
 *
 * @property changes Syntax-level change categories, independent of the safety outcome.
 * @property code Machine-readable proof outcome.
 * @property message Human-readable explanation of the outcome.
 * @property locations Diff spans for the obligation; may be empty for a pure file move.
 * @property scenario Name of the folding scenario used for this proof.
 * @property basePath Repository-relative old path, or null if absent or unspecified.
 * @property headPath Repository-relative new path, or null if absent or unspecified.
 */
data class VerdictReason(
    val changes: Set<ChangeKind>,
    val code: ReasonCode,
    val message: String,
    val locations: List<SourceLocation>,
    val scenario: String,
    val basePath: String? = null,
    val headPath: String? = null,
)
