package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) throw new IllegalStateException(); return x + 1; }
}
