package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return x + 1
        val ordered = listOf("aaa", "b", "cc").sortedBy { -it.length }
        return ordered.first().length
    }
}
