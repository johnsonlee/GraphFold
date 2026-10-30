package sample;
public class Example {
    public int run(int x) { return x + Additional.VALUE; }
    public boolean gate() { return Ab.enabled("feature"); }
}
class Additional {
    static final int VALUE = 1;
    static { System.out.println("before"); }
}
