package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) {
            return java.util.Optional.of(x).map { value -> value + 2 }.orElse(0)
        }
        return 0
    }
}
