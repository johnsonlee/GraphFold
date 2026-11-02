package sample;
public class Example {
    static { if (Ab.enabled("feature")) System.out.println("after"); }
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
