fun value(ctx: Context?): Int {
return 1
}
class Context(private val device: Device?) { fun getDevice(): Device? = device }
class Device(private val platform: String?) { fun getPlatform(): String? = platform }
