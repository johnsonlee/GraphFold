package sample;
public class Example {
    public int run(int x) {
        int a = Keys.enabled("experiment") ? x + 20 : x + 1;
        int b = Keys.version() >= 2 ? x * 20 : x * 2;
        return a + b;
    }
}
