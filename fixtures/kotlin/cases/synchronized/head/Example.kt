package sample
class Example {
    @Synchronized fun run(x: Int): Int { if (Ab.enabled("feature")) return 0; return x + 1; }
}
