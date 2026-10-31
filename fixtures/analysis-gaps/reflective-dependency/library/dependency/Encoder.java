package dependency;
public final class Encoder {
    public static String encode(Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = value.getClass().getDeclaredFields()[0];
        field.setAccessible(true);
        return field.getName() + "=" + field.get(value);
    }
}
