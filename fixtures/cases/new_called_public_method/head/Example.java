package sample;
public class Example {
    public int run(int x) { if (Ab.enabled("feature")) return 0; added(); return x + 1; }
    public void added() { System.out.println("uncontrolled"); }
}
