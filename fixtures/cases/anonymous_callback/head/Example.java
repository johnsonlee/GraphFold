package sample;
public class Example {
 public boolean gate(){return Ab.enabled("feature");}
 public Runnable create(){return new Runnable(){ public void run(){System.out.println("new");} };}
}
