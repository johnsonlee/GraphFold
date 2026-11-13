package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return Treatment().apply(x); return x + 1 }
    private class Treatment {
        fun apply(x: Int): Int { return x * 10 }
    }
}
