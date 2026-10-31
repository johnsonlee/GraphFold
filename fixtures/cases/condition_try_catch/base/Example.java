package sample;
public class Example {
    public int run(int x) {
        try { return 10 / x; }
        catch (ArithmeticException error) { return -1; }
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
