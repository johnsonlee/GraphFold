package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return x > 0 || x == -1 ? 1 : 0;
        }
        return 0;
    }
}
