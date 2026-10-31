package sample

class Example {
    fun run(x: Int): Int {
        val action = object : java.util.function.IntSupplier {
            override fun getAsInt(): Int = x + if (Ab.enabled("feature")) 2 else 1
        }
        return action.asInt
    }
}
