package sample;
public class Example {
    public int run() {
        if (Ab.enabled("feature")) {
            Runnable added = new Runnable() { public void run() {} };
            added.run();
            return 10;
        }
        return 1;
    }
}
