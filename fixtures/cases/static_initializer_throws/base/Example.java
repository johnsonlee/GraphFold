package sample;
public class Example {
    static { initialize(); }
    private static void initialize() {}
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
