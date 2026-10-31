package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) throw IllegalStateException(); return x + 1; }
}
