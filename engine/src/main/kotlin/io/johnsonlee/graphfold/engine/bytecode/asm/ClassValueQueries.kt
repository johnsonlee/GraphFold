package io.johnsonlee.graphfold.engine.bytecode.asm

/** Class queries that inspect an existing type without discovering or invoking its members. */
internal object ClassValueQueries {
    val signatures = setOf(
        "desiredAssertionStatus()Z", "getName()Ljava/lang/String;", "getTypeName()Ljava/lang/String;",
        "getSimpleName()Ljava/lang/String;", "getPackageName()Ljava/lang/String;",
        "isInstance(Ljava/lang/Object;)Z", "cast(Ljava/lang/Object;)Ljava/lang/Object;",
        "isAssignableFrom(Ljava/lang/Class;)Z", "isArray()Z", "isInterface()Z",
    )

    // cast can expose runtime names in ClassCastException even without a successful value result.
    val runtimeNameMembers = setOf("getName", "getTypeName", "getSimpleName", "getPackageName", "cast")
}
