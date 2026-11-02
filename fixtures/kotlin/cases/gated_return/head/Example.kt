package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return x * 10; return x + 1; }
}
