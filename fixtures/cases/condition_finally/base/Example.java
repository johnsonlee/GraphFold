package sample;
public class Example {
    public int run(int x) {
        int[] value = {x};
        try { value[0]++; } finally { value[0] += 1; }
        return value[0];
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
