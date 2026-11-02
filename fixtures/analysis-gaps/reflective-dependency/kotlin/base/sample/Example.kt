package sample
class Example {
    private var amount = 1
    fun run(): String { return dependency.Encoder.encode(this) }
}
