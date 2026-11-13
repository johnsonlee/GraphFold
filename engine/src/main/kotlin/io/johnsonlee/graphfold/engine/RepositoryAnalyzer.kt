package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.GitWorkspace
import io.johnsonlee.graphfold.engine.git.GitWorkspaceManager
import io.johnsonlee.graphfold.engine.git.WorkspaceLeaseTimeoutException
import io.johnsonlee.graphfold.engine.git.deleteDirectory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import java.nio.file.Files

/** Git orchestration around explicitly supplied static inputs; the integration owns input preparation. */
class RepositoryAnalyzer(
    private val configuration: RepositoryConfiguration, private val provider: RevisionInputProvider,
) {
    @Suppress("TooGenericExceptionCaught") // Public orchestration boundary must not turn failed preparation into PASS.
    fun analyze(request: AnalysisRequest): AnalysisResult {
        val progress = Progress(AnalysisStage.INPUT, request.base, request.head)
        return try {
            FoldDocument.validate(request.fold, configuration.maximumScenarios)
            progress.stage = AnalysisStage.GIT
            Files.createDirectories(configuration.cacheDirectory)
            GitWorkspaceManager(configuration.cacheDirectory.resolve("git"), configuration.maximumWorkspaceSlots,
                configuration.workspaceLeaseTimeout).open(request.repository, request.base, request.head).use { workspace ->
                analyze(request, workspace, progress)
            }
        } catch (failure: Exception) {
            val code = when (failure) {
                is WorkspaceLeaseTimeoutException -> DiagnosticCode.WORKSPACE_CAPACITY_TIMEOUT
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    DiagnosticCode.ANALYSIS_CANCELLED
                }
                else -> DiagnosticCode.ANALYSIS_FAILED
            }
            AnalysisReports.preparationFailure(progress.baseSha, progress.headSha, request.fold, configuration.maximumScenarios,
                Diagnostic(progress.stage, code, failure.toString()))
        }
    }

    private fun analyze(request: AnalysisRequest, workspace: GitWorkspace, progress: Progress): AnalysisResult {
        progress.baseSha = workspace.baseSha
        progress.headSha = workspace.headSha
        val changes = workspace.changes()
        val run = Files.createTempDirectory(configuration.cacheDirectory, "prepared-")
        try {
            progress.stage = AnalysisStage.INPUT
            val (base, head) = if (changes.isEmpty()) {
                PreparedRevision(workspace.baseSha, workspace.baseDirectory, emptyMap(), null) to
                    PreparedRevision(workspace.headSha, workspace.headDirectory, emptyMap(), null)
            } else {
                provider.prepare(workspace.baseSha, workspace.baseDirectory, run.resolve("base")) to
                    provider.prepare(workspace.headSha, workspace.headDirectory, run.resolve("head"))
            }
            progress.stage = AnalysisStage.GRAPH
            return GraphFoldEngine(EngineConfiguration(configuration.cacheDirectory.resolve("engine"),
                configuration.maximumScenarios)).analyze(PreparedAnalysisRequest(base, head, changes, request.fold))
        } finally { deleteDirectory(run) }
    }

    private class Progress(var stage: AnalysisStage, var baseSha: String, var headSha: String)
}
