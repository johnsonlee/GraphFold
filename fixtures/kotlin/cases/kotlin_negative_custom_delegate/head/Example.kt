package sample

import kotlin.reflect.KProperty

class Example {
    private class OffsetDelegate {
        operator fun getValue(owner: Any?, property: KProperty<*>): Int =
            if (Ab.enabled("feature")) 3 else 1
    }
    private val offset by OffsetDelegate()
    fun run(x: Int): Int = x + offset
}
