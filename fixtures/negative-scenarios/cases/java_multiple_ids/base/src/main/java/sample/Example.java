package sample;
public class Example {
    public int run(int x) {
        int a = Keys.enabled("first") ? x + 10 : x + 1;
        int b = Keys.enabled("second") ? x * 10 : x * 2;
        return a + b;
    }
}
