package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            if (x < 0) return -1;
            return x * 2;
        }
        return 0;
    }
}
