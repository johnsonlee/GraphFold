package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            int[] value = {x};
            try { value[0]++; } finally { value[0] += 2; }
            return value[0];
        }
        return 0;
    }
}
