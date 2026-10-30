package io.johnsonlee.graphfold.engine.git

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/** Raw object checkout deliberately never executes hooks, clean/smudge filters, or external Git. */
internal fun checkout(cached: Repository, revision: String, directory: Path) {
    Files.createDirectories(directory)
    val gitDirectory = directory.resolve(".git")
    JGitRepositories.open(gitDirectory, directory).use { checkout -> checkout.create(false) }
    val alternate = gitDirectory.resolve("objects/info/alternates")
    Files.createDirectories(alternate.parent)
    Files.writeString(alternate, cached.directory.toPath().resolve("objects").toAbsolutePath().toString() + "\n")
    // Reopen after persisting alternates so both this process and later build tools see cached objects.
    JGitRepositories.open(gitDirectory, directory).use { checkout ->
        val commit = RevWalk(cached).use { it.parseCommit(cached.resolve(revision)) }
        TreeWalk(cached).use { tree ->
            tree.addTree(commit.tree)
            tree.isRecursive = true
            checkoutTree(cached, checkout, tree, directory)
        }
        checkout.updateRef(Constants.HEAD, true).apply {
            setNewObjectId(commit)
            isForceUpdate = true
        }.forceUpdate()
    }
}

private fun checkoutTree(cached: Repository, checkout: Repository, tree: TreeWalk, directory: Path) {
    val index = checkout.lockDirCache()
    try {
        val builder = index.builder()
        while (tree.next()) {
            checkGitCancellation()
            val mode = tree.getFileMode(0)
            materialize(cached, tree, directory, mode)
            builder.add(DirCacheEntry(tree.pathString).apply {
                fileMode = mode
                setObjectId(tree.getObjectId(0))
            })
        }
        builder.finish()
        index.write()
        check(index.commit()) { "Cannot persist checkout index" }
    } finally {
        index.unlock()
    }
}

private fun materialize(repository: Repository, tree: TreeWalk, directory: Path, mode: FileMode) {
    if (mode == FileMode.GITLINK) {
        throw GitOperationException("Submodule inputs are not supported; analysis cannot be complete")
    }
    val destination = directory.resolve(tree.pathString).normalize()
    if (!destination.startsWith(directory) || tree.pathString.split('/').any { it.equals(".git", ignoreCase = true) }) {
        throw GitOperationException("Unsafe checkout path")
    }
    Files.createDirectories(destination.parent)
    val loader = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB)
    if (mode == FileMode.SYMLINK) {
        Files.createSymbolicLink(destination, Path.of(String(loader.bytes, Charsets.UTF_8)))
    } else {
        writeBlob(loader, destination, mode)
    }
}

private fun writeBlob(loader: ObjectLoader, destination: Path, mode: FileMode) {
    loader.openStream().use { input ->
        val prefix = input.readNBytes(LFS_PREFIX.length)
        if (String(prefix, Charsets.UTF_8) == LFS_PREFIX) {
            throw GitOperationException("Git LFS inputs are not supported; analysis cannot be complete")
        }
        Files.newOutputStream(destination).use { output ->
            output.write(prefix)
            copy(input, output)
        }
    }
    if (mode == FileMode.EXECUTABLE_FILE && !destination.toFile().setExecutable(true, false)) {
        throw GitOperationException("Cannot preserve executable file mode")
    }
}

private fun copy(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var count = input.read(buffer)
    while (count >= 0) {
        checkGitCancellation()
        output.write(buffer, 0, count)
        count = input.read(buffer)
    }
}

private const val LFS_PREFIX = "version https://git-lfs.github.com/spec/v1"
