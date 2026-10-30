package sample;
public class Example {
    public int run(int x) {
        if (Ab.enabled("feature")) {
            return switch (x) { case 1 -> 10; case 2 -> 20; default -> 30; };
        }
        return 0;
    }
}
