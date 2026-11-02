package io.johnsonlee.graphfold.engine.git

import java.nio.file.Path
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS

/** Explicit directories avoid Git environment overrides; the private FS skips its optional native config probe. */
internal object JGitRepositories {
    fun open(directory: Path, workTree: Path? = null): Repository {
        val builder = FileRepositoryBuilder()
            .setFS(FS.DETECTED.newInstance().setGitSystemConfig(null))
            .setGitDir(directory.toFile())
        if (workTree == null) builder.setBare() else builder.setWorkTree(workTree.toFile())
        return builder.build()
    }
}
