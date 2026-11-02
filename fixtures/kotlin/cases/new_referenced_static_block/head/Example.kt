package sample
class Example {
    fun run(x: Int): Int { Additional.touch(); return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
object Additional {
    init { System.out.println("initialized") }
    fun touch() {}
}
