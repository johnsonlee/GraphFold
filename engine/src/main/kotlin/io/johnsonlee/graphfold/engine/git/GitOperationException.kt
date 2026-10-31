package io.johnsonlee.graphfold.engine.git

/** Failure text intentionally excludes URLs, credentials and transport exception details. */
open class GitOperationException(message: String) : IllegalStateException(message)

/** Temporary capacity contention; callers can retry or adjust the workspace budget. */
class WorkspaceLeaseTimeoutException : GitOperationException(
    "Workspace capacity exhausted; timed out waiting for a reusable checkout slot. Retry or increase the slot/lease budget.",
)
