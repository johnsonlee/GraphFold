package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.asm.LeafApplicationDispatch
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphite.core.CallSiteNode

/** Derived graph targets do not establish the identity of an actual surviving JVM invocation. */
internal fun LeafApplicationDispatch.proves(site: CallSiteNode, methods: Map<String, MethodSnapshot>): Boolean =
    site.origin == null && (site.ordinal ?: -1) >= 0 &&
        site.callee.identity in methods[site.caller.identity]?.calls.orEmpty() && proves(site.callee.identity)
