package sample
class Example {
    fun run(x: Int): Int { return x + Additional.VALUE }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
object Additional {
    const val VALUE = 1
    init { System.out.println("after") }
}
