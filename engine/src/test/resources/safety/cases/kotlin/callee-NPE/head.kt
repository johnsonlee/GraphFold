fun value(text: String?): Int {
val ignored = "Android".equals(platform(text))
return 1
}
private fun platform(text: String?): String? { val broken: String? = null; broken!!.length; return text }
