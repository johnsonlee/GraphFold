package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return helper(x); return helper(x); }
    private fun helper(x: Int): Int { return x + 1; }
}
