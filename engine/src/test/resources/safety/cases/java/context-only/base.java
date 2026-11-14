public class Example {
 public static int value(Context ctx) {
return 1;
 }
public static final class Context {
    private final Device device;
    public Context(Device device) { this.device = device; }
    public Device getDevice() { return device; }
}
public static final class Device {
    private final String platform;
    public Device(String platform) { this.platform = platform; }
    public String getPlatform() { return platform; }
}
}
