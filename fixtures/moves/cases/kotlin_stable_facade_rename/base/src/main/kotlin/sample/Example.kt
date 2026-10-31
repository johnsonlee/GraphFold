@file:JvmName("StableFacade")
package sample

fun run(x: Int): Int { return x + 1 }
fun gate(): Boolean { return Ab.enabled("feature") }
