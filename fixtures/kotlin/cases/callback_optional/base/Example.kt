package sample

class Example {
    fun run(x: Int): Int {
        return java.util.Optional.of(x).map { value -> value + 1 }.orElse(0)
    }
    fun gate(): Boolean { return Ab.enabled("feature") }
}
