package sample;
public class Gap {
    private void use(Object value) {}
    private void unused() { use(new Object() {}); use(new Object() {}); }
}
