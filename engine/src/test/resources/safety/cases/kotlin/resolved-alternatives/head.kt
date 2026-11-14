fun value(text: String?, choose: Boolean): Int {
if (choose) { first(text) } else { second(text) }
return 1
}
private fun first(text: String?): String? = text
private fun second(text: String?): String? = text
