package sample
class Example {
    fun run(values: List<Int>): List<Int> = values.stream().filter { value ->
        if (Ab.enabled("feature")) value > 0 && value < 10 else value > 0
    }.toList()
}
