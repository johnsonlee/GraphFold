fun value(text: String?, choose: Boolean): Int {
val selected = if (choose) text else "ok"; val ignored = selected!!.length
return 1
}
