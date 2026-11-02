package sample
class Observer {
    fun inspect(value: Any) { if (Ab.enabled("feature")) { java.beans.XMLEncoder(java.io.ByteArrayOutputStream()).writeObject(value) } }
    fun gate(): Boolean = Ab.enabled("feature")
    fun callback(): Runnable = object : Runnable { override fun run() {} }
}
