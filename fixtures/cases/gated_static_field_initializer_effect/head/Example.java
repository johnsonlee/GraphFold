package sample;
public class Example {
    private static final int unused = initialize();
    private static int initialize() { if (Ab.enabled("feature")) System.out.println("after"); return 1; }
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
