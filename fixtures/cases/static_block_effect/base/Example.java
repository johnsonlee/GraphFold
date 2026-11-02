package sample;
public class Example {
    static { System.out.println("before"); }
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
