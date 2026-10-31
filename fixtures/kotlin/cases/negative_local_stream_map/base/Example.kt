package sample
class Example {
    fun run(values: List<Int>): List<Int> = values.stream().map { value ->
        if (Ab.enabled("feature")) value + 10 else value + 1
    }.toList()
}
