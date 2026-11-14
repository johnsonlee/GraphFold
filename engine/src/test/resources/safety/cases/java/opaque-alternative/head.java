public class Example {
 public static int value(String text, boolean choose) {
if (choose) { first(text); } else { second(text); }
return 1;
 }
private static String first(String text) { return text; }
private static native String second(String text);
}
