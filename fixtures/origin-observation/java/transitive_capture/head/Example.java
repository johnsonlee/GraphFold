package sample;
import java.util.function.Supplier;
public class Example {
    public String run() {
        if (Ab.enabled("feature")) { new Supplier<String>() { public String get() { return "unused"; } }.get(); }
        return new Supplier<String>() { public String get() { return capture().getStackTrace()[1].getClassName(); } }.get();
    }
    private Throwable capture() { return new RuntimeException(); }
    public boolean gate() { return Ab.enabled("feature"); }
}
