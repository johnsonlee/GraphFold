package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        return new Supplier<String>() { public String get() { return "live"; } }.toString();
    }
    public boolean gate() { return Ab.enabled("feature"); }
}
