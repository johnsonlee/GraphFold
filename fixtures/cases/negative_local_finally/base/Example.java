package sample;
public class Example {
    public int run(StringBuilder trace, int value) {
        try {
            if (Ab.enabled("feature")) return value + 1;
            return value * 2;
        } finally {
            trace.append("finally");
        }
    }
}
