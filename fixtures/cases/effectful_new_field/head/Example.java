package sample;
public class Example {
    private final int unused = initialize();
    private static int initialize() { System.out.println("initialized"); return 1; }
    public int run(int x) { return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
