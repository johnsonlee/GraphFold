package sample
class Gap {
    private fun use(value: Any) {}
    private fun unused() { use(object : Runnable { override fun run() {} }); use(object : Runnable { override fun run() {} }) }
}
