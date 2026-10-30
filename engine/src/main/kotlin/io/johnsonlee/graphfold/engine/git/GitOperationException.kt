package io.johnsonlee.graphfold.engine.git

/** Failure text intentionally excludes URLs, credentials and transport exception details. */
class GitOperationException(message: String) : IllegalStateException(message)
