package sample
class Example {
    fun run(x: Int): Int { if (!Ab.enabled("feature")) return x + 1; return x * 10; }
}
