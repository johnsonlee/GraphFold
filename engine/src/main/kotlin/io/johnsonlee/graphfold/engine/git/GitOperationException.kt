package io.johnsonlee.graphfold.engine.git

/**
 * A Git operation failed without exposing its transport exception or credential-bearing cause.
 *
 * @param message Caller-visible failure description; callers must omit URLs, credentials, and transport details.
 */
open class GitOperationException(message: String) : IllegalStateException(message)

/** Temporary capacity contention; callers can retry or adjust the workspace budget. */
class WorkspaceLeaseTimeoutException : GitOperationException(
    "Workspace capacity exhausted; timed out waiting for a reusable checkout slot. Retry or increase the slot/lease budget.",
)
