package sample
class Example {
 fun gate(): Boolean{return Ab.enabled("feature");}
 fun create(): Runnable{return object : Runnable { override fun run(){System.out.println("new");} };}
}
