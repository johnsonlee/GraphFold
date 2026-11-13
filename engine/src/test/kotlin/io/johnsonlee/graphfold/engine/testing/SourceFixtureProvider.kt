package io.johnsonlee.graphfold.engine.testing

import io.johnsonlee.graphfold.engine.RepositoryAnalyzer
import io.johnsonlee.graphfold.engine.RepositoryConfiguration
import io.johnsonlee.graphfold.engine.RevisionInputProvider
import io.johnsonlee.graphfold.engine.inputs.archive.DependencyInputCache
import io.johnsonlee.graphfold.engine.inputs.archive.prepareRevision
import java.time.Duration

/** Explicit test integration keeps compiler selection outside the production engine. */
internal fun sourceAnalyzer(configuration: RepositoryConfiguration): RepositoryAnalyzer = RepositoryAnalyzer(configuration,
    RevisionInputProvider { sha, checkout, output ->
        val project = SourceFixtureCompiler(Duration.ofMinutes(2)).compile(checkout, output.resolve("compiled"))
        prepareRevision(sha, checkout, project, output.resolve("prepared"), DependencyInputCache())
    })
