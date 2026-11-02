package sample;
interface Value<T> { T value(); }
public class Example implements Value<String> {
    public String value() {
        if (Ab.enabled("feature")) return "value2";
        return "control";
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
