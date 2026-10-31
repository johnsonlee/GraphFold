package sample
class Example {
    fun run(x: Int): Int { if (Ab.enabled("feature")) return 0; return x + 1; }
    fun added() { System.out.println("uncontrolled"); }
}
