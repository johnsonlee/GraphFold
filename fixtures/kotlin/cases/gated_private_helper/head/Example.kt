package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return treatment(x); return x + 1; }
    private fun treatment(x: Int): Int { return x * 10; }
}
