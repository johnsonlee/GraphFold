package sample;
public class Example {
    public int run(int x) {
        int group = Keys.group("experiment");
        if (group == 0 || group == -1) return x + 1;
        return x + 10;
    }
}
