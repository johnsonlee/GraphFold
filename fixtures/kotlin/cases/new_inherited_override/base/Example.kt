package sample
class Example : java.util.ArrayList<String>() {
    fun run(x: Int): Int { return x }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
