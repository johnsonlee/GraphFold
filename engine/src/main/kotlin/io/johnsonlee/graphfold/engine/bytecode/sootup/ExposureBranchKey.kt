package io.johnsonlee.graphfold.engine.bytecode.sootup

import sootup.core.jimple.common.stmt.Stmt

/** Exact pre-fold branch identity; ambiguous or missing source coordinates never authorize normalization. */
internal data class ExposureBranchKey(val condition: String, val position: String)

internal fun exposureBranchKey(statement: Stmt): ExposureBranchKey? {
    val position = statement.positionInfo.stmtPosition
    return if (statement.isJIfStmt && position.firstLine > 0) {
        ExposureBranchKey(statement.asJIfStmt().condition.toString(), position.toString())
    } else null
}
