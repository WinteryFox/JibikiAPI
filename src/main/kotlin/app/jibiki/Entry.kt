package app.jibiki

data class Entry(
        val kanji: String,
        val reading: String,
        val forms: Array<String?>,
        val glossary: Array<String?>
)