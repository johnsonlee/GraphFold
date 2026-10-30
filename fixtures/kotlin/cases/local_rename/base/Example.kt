package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 3; val total = x + 1; return total; }
}
