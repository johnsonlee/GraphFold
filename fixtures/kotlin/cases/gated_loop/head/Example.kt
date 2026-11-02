package sample
class Example {
    fun run(input: Int): Int { var x = input; if (Ab.enabled("feature")) { var i = 0; while (i < x) { x += 2; i++ }; return x; }; return x + 1; }
}
