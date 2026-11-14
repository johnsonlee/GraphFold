public class Example {
 public static int value(Reader reader, String text) {
if (reader != null) { reader.read(text); }
return 1;
 }
public interface Reader { String read(String text); }
}
