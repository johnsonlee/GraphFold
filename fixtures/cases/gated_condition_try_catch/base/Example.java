package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            try { return 10 / x; }
            catch (ArithmeticException error) { return -1; }
        }
        return 0;
    }
}
