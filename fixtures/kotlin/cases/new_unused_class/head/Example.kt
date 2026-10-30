package sample
class Example {
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
class Additional {
    fun apply(x: Int): Int { return x + 1 }
}
