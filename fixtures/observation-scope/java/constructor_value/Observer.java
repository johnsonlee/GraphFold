package sample;
public class Observer {
    public void inspect(Object value) { new java.beans.XMLEncoder(new java.io.ByteArrayOutputStream()).writeObject(value); }
    public boolean gate() { return Ab.enabled("feature"); }
    public Runnable callback() { return new Runnable() { public void run() {} }; }
}
