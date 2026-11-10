package io.johnsonlee.graphfold.engine.git

import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder

/** Built-in transports only: local upload-pack and SSH both run inside this JVM. */
internal fun fetch(
    repository: Repository, source: String, ref: String,
    openTransport: (Repository, URIish) -> Transport = { local, remote -> Transport.open(local, remote) },
    nanoTime: () -> Long = System::nanoTime,
) {
    checkGitCancellation()
    val uri = URIish(source)
    val local = uri.scheme == "file" || uri.scheme == null && uri.host == null
    if (ref.length == FULL_SHA_LENGTH && local) {
        JGitLocalObjects.importCommit(repository, uri, ref)
        return
    }
    val deadline = nanoTime() + TIMEOUT_SECONDS * NANOS_PER_SECOND
    openTransport(repository, uri).use { transport ->
        transport.setOptionUploadPack("git-upload-pack")
        transport.timeout = TIMEOUT_SECONDS
        transport.tagOpt = TagOpt.NO_TAGS
        // Do not inherit an externally installed provider or invoke a native credential helper.
        transport.credentialsProvider = null
        val ssh = if (transport is SshTransport) SshdSessionFactoryBuilder()
            .setHomeDirectory(repository.fs.userHome())
            .setSshDirectory(repository.fs.userHome().resolve(".ssh"))
            .build(null) else null
        try {
            if (transport is SshTransport) transport.sshSessionFactory = ssh
            transport.fetch(DeadlineMonitor(deadline, nanoTime), listOf(RefSpec(ref)))
            checkGitCancellation()
            if (nanoTime() - deadline >= 0) throw GitOperationException("Git operation timed out")
        } finally {
            ssh?.close()
        }
    }
}

private class DeadlineMonitor(private val deadline: Long, private val nanoTime: () -> Long) : ProgressMonitor {
    override fun start(totalTasks: Int) = Unit
    override fun beginTask(title: String, totalWork: Int) = Unit
    override fun update(completed: Int) = Unit
    override fun endTask() = Unit
    override fun showDuration(enabled: Boolean) = Unit
    override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted || nanoTime() - deadline >= 0
}

private const val FULL_SHA_LENGTH = 40
private const val TIMEOUT_SECONDS = 300
private const val NANOS_PER_SECOND = 1_000_000_000L
