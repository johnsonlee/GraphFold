package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 0; else return x + 1; }
}
