package sample;
public class Example {
    public int run(int x) {
        int a = Keys.enabled("first") ? x + 20 : x + 1;
        int b = Keys.enabled("second") ? x * 20 : x * 2;
        return a + b;
    }
}
