fun value(reader: Reader?, text: String?): Int {
if (reader != null) { reader.read(text) }
return 1
}
interface Reader { fun read(text: String?): String? }
