package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) { new Runnable() { public void run() {} }; } new Runnable() { public void run() {} }.run(); return x; }
    public boolean gate() { return Ab.enabled("feature"); }
}
