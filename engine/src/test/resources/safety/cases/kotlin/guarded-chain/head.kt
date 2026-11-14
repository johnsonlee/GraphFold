fun value(ctx: Context?): Int {
if (ctx != null) { val device = ctx.getDevice(); if (device != null) { val ignored = "Android".equals(device.getPlatform()) } }
return 1
}
class Context(private val device: Device?) { fun getDevice(): Device? = device }
class Device(private val platform: String?) { fun getPlatform(): String? = platform }
