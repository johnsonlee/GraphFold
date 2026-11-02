package io.johnsonlee.graphfold.engine

/** Names inherited by external builds. Additional credentials require explicit SDK opt-in. */
val DEFAULT_BUILD_ENVIRONMENT: Set<String> = setOf(
    "PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT",
    "JAVA_HOME", "GRADLE_USER_HOME", "MAVEN_USER_HOME", "TMPDIR", "TMP", "TEMP",
    "LANG", "LC_ALL", "LC_CTYPE", "TZ",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy",
)
