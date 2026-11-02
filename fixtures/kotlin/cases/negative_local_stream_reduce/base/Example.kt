package sample
class Example {
    fun run(values: List<Int>): Int = values.stream().reduce(0) { sum, value ->
        if (Ab.enabled("feature")) sum + value + 1 else sum + value
    }
}
