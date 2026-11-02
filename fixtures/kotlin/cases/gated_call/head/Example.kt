package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) log(x); return x + 1; }
    private fun log(x: Int) { System.out.println(x); }
}
