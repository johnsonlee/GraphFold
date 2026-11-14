package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import java.nio.file.Path
import java.time.Duration

/** Default number of concurrent checkout leases for one repository. */
const val DEFAULT_MAXIMUM_WORKSPACE_SLOTS = 2

/** Default checkout-acquisition budget; unrelated to a build tool or compiler deadline. */
fun defaultWorkspaceLeaseTimeout(): Duration = Duration.ofMinutes(DEFAULT_WORKSPACE_LEASE_MINUTES)

private const val DEFAULT_WORKSPACE_LEASE_MINUTES = 25L

/** Git revision selection and explicit control assumptions. */
data class AnalysisRequest(val repository: String, val base: String, val head: String, val fold: String) {
    init {
        require(repository.isNotBlank()) { "repository must not be blank" }
        require(base.isNotBlank()) { "base must not be blank" }
        require(head.isNotBlank()) { "head must not be blank" }
        require(fold.isNotBlank()) { "fold must not be blank" }
    }
}

/** Tool-independent repository and static-analysis resource limits. */
data class RepositoryConfiguration @JvmOverloads constructor(
    val cacheDirectory: Path,
    val maximumScenarios: Int = 32,
    val maximumWorkspaceSlots: Int = DEFAULT_MAXIMUM_WORKSPACE_SLOTS,
    val workspaceLeaseTimeout: Duration = defaultWorkspaceLeaseTimeout(),
) {
    init {
        require(maximumScenarios > 0) { "maximum scenarios must be positive" }
        require(maximumWorkspaceSlots > 0) { "maximum workspace slots must be positive" }
        require(!workspaceLeaseTimeout.isNegative && !workspaceLeaseTimeout.isZero) { "lease timeout must be positive" }
    }
}

/** Integrations provide prepared bytes for an immutable Git checkout; engine never chooses a compiler or build tool. */
fun interface RevisionInputProvider {
    /** Returns static inputs bound to [sha]; [output] is an integration-owned temporary directory. */
    fun prepare(sha: String, checkout: Path, output: Path): PreparedRevision
}
