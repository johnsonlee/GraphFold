package sample;
public class Example {
    public int run(int value) {
        int result = helper(value);
        if (Ab.enabled("feature")) result += helper(value + 1);
        return result;
    }
    private int helper(int value) {
        if (Ab.enabled("feature")) return value + 2;
        return value * 2;
    }
}
