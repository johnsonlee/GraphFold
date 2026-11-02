package sample;
public class Example {
    public int run(int x) { new Runnable() { public void run() {} }.run(); return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
