package sample

class Example {
    fun run(x: Int): Int = sequenceOf(x, 1, 2)
        .filter { it > 0 && (!Ab.enabled("feature") || it > 2) }
        .map { it + 1 }
        .sum()
}
