package io.johnsonlee.graphfold.engine

import java.nio.file.Path
import java.time.Duration

val DEFAULT_BUILD_TIMEOUT: Duration = Duration.ofMinutes(10)
private const val DEFAULT_MAXIMUM_SCENARIOS = 32
const val DEFAULT_MAXIMUM_WORKSPACE_SLOTS = 2
private const val REVISIONS_PER_ANALYSIS = 2L
private const val WORKSPACE_PREPARATION_MARGIN_MINUTES = 5L

/** Waiting-budget heuristic based on two external-build timeouts plus preparation; not a total analysis deadline. */
fun defaultWorkspaceLeaseTimeout(buildTimeout: Duration = DEFAULT_BUILD_TIMEOUT): Duration =
    buildTimeout.multipliedBy(REVISIONS_PER_ANALYSIS).plusMinutes(WORKSPACE_PREPARATION_MARGIN_MINUTES)

/** All business inputs to an analysis. [fold] is a Graphite fold document, optionally containing scenarios. */
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

enum class Verdict { PASS, FAIL }
enum class Revision { BASE, HEAD }
enum class FindingKind { UNCONTROLLED_CHANGE, UNVERIFIABLE_CHANGE }
enum class AnalysisStage { INPUT, GIT, BUILD, SOURCE, GRAPH, FOLD, COMPARE, REPORT }

/** One-based coordinates. The end position of a location is exclusive. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start > 0 && end > 0) { "coordinates must be positive" }
    }
}

/** Columns are UTF-16 offsets within their respective lines, not display widths. */
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

data class Evidence(val description: String, val location: SourceLocation? = null)

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
    WORKSPACE_CAPACITY_TIMEOUT, ANALYSIS_CANCELLED, ANALYSIS_FAILED,
    UNVERIFIED_ORIGIN, INCOMPLETE_GRAPH, UNMATCHED_RULE, UNMAPPED_CHANGE,
    SOURCE_RESOLUTION_FAILED, UNLOCATED_MOVE_DIFFERENCE,
}

data class Diagnostic(
    val stage: AnalysisStage,
    val code: DiagnosticCode,
    val message: String,
    val blocking: Boolean = true,
)

data class AnalysisMetadata(
    val baseSha: String,
    val headSha: String,
    val scenarios: List<String>,
    val obligations: Int,
    val provedObligations: Int,
    val graphiteVersion: String = "2.12.0",
)

/** Verdict is derived; callers cannot accidentally construct PASS with unresolved findings. */
data class AnalysisResult(
    val findings: List<Finding>,
    val diagnostics: List<Diagnostic>,
    val metadata: AnalysisMetadata,
    val reasons: List<VerdictReason> = emptyList(),
    val ruleAudits: List<RuleAudit> = emptyList(),
) {
    val schemaVersion: Int = 1
    val verdict: Verdict
        get() = if (findings.isEmpty() && diagnostics.none { it.blocking } &&
            metadata.obligations == metadata.provedObligations
        ) Verdict.PASS else Verdict.FAIL
}

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
    FIELD_ADDED, FIELD_REMOVED, FIELD_INITIALIZER_CHANGED, FIELD_RENAMED,
    CONDITION_ADDED, CONDITION_REMOVED, CONDITION_CHANGED, CALL_CHANGED,
    METHOD_ADDED, METHOD_REMOVED, METHOD_RENAMED, TYPE_ADDED, TYPE_REMOVED,
    DECLARATION_CHANGED, BODY_CHANGED, SOURCE_ONLY, FILE_MOVED, FILE_RENAMED, CLASS_INITIALIZATION_CHANGED,
}

enum class ReasonCode { FOLDED_EQUIVALENT, UNREACHABLE, SURVIVING_DIFFERENCE, ANALYSIS_INCOMPLETE }

data class VerdictReason(
    val changes: Set<ChangeKind>,
    val code: ReasonCode,
    val message: String,
    val locations: List<SourceLocation>,
    val scenario: String,
    val basePath: String? = null,
    val headPath: String? = null,
)
