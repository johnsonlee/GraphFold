package sample

class Example {
    fun run(x: Int): Int {
        if (Ab.enabled("feature")) return -1
        val action = object : java.util.function.IntSupplier { override fun getAsInt(): Int = x + 2 }; return action.asInt
    }
}
