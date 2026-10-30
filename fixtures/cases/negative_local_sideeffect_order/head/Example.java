package sample;
public class Example {
    public int run(StringBuilder trace, int value) {
        trace.append("before");
        int result = value;
        if (Ab.enabled("feature")) result += 2;
        trace.append("after");
        return result;
    }
}
