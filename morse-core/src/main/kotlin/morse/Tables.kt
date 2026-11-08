package morse

enum class Alphabet { RU, EN }

/** Morse code tables. Russian and Latin share most codes, so the alphabet is a user choice. */
object MorseTables {
    val RU: Map<String, String> = mapOf(
        ".-" to "А", "-..." to "Б", ".--" to "В", "--." to "Г", "-.." to "Д", "." to "Е", "...-" to "Ж",
        "--.." to "З", ".." to "И", ".---" to "Й", "-.-" to "К", ".-.." to "Л", "--" to "М", "-." to "Н",
        "---" to "О", ".--." to "П", ".-." to "Р", "..." to "С", "-" to "Т", "..-" to "У", "..-." to "Ф",
        "...." to "Х", "-.-." to "Ц", "---." to "Ч", "----" to "Ш", "--.-" to "Щ", "--.--" to "Ъ",
        "-.--" to "Ы", "-..-" to "Ь", "..-.." to "Э", "..--" to "Ю", ".-.-" to "Я",
    )

    val EN: Map<String, String> = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E", "..-." to "F", "--." to "G",
        "...." to "H", ".." to "I", ".---" to "J", "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N",
        "---" to "O", ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T", "..-" to "U",
        "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y", "--.." to "Z",
    )

    /** Digits and the three prosigns that matter most. */
    val COMMON: Map<String, String> = mapOf(
        "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4", "....." to "5",
        "-...." to "6", "--..." to "7", "---.." to "8", "----." to "9",
        ".-.-." to "<AR>", "-...-" to "=", "...-.-" to "<SK>",
    )

    /** Off by default: in noisy audio a stray element turns a letter into punctuation. */
    val PUNCT: Map<String, String> = mapOf(
        "..--.." to "?", ".-.-.-" to ".", "--..--" to ",", "-..-." to "/",
    )

    fun build(alphabet: Alphabet, punctuation: Boolean = false): Map<String, String> {
        val m = HashMap<String, String>()
        m.putAll(if (alphabet == Alphabet.RU) RU else EN)
        m.putAll(COMMON)
        if (punctuation) m.putAll(PUNCT)
        return m
    }
}
