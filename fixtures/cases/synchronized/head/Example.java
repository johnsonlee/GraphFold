package sample;
public class Example {
    public synchronized int run(int x) { if (Ab.enabled("feature")) return 0; return x + 1; }
}
