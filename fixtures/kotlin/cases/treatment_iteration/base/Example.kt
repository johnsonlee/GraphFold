package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return x * 2; return x + 1; }
}
