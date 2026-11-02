package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return x * 3; return x + 1; }
}
