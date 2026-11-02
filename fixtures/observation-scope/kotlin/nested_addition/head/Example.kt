package sample
class Example {
    fun run(): Int {
        if (Ab.enabled("feature")) {
            val added = object : Runnable { override fun run() {} }
            added.run()
            return 10
        }
        return 1
    }
}
