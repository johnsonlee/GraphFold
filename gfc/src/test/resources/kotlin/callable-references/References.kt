package sample

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun top(value: Int): Int = value + 1

class Receiver(val value: Int, var mutable: Int) {
    fun member(input: Int): Int = value + input
}

class Example {
    fun run(): Int {
        val receiver = Receiver(3, 4)
        val function = ::top
        val bound = receiver::member
        val unbound = Receiver::member
        val javaMethod: (Path, Array<out LinkOption>) -> Boolean = Files::isRegularFile
        val property = receiver::value
        val unboundProperty = Receiver::value
        val mutableProperty = receiver::mutable
        val unboundMutableProperty = Receiver::mutable
        val actualObject = object : java.util.function.IntSupplier {
            override fun getAsInt(): Int = 7
        }
        mutableProperty.set(5)
        unboundMutableProperty.set(receiver, 6)
        return function(1) + bound(2) + unbound(receiver, 3) + property.get() +
            unboundProperty.get(receiver) + mutableProperty.get() + unboundMutableProperty.get(receiver) +
            actualObject.asInt + if (javaMethod(Path.of("missing-callable-reference-fixture"), emptyArray())) 1 else 0
    }
}
