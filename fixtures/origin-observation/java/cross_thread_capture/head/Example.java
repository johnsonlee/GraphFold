package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        if (Ab.enabled("feature")) { new Supplier<String>() { public String get() { return "unused"; } }.get(); }
        return new Supplier<String>() { public String get() { return "live"; } }.get();
    }
    public Object observe() { return Thread.getAllStackTraces(); }
    public boolean gate() { return Ab.enabled("feature"); }
}
