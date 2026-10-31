package sample;
public class Example {
    private static final int unused = initialize();
    private static int initialize() { System.out.println("before"); return 1; }
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
