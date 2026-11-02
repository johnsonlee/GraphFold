package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 3; val sum = x + 1; return sum; }
}
