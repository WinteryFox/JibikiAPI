package app.jibiki

data class Entry(
        val kanji: String,
        val reading: String,
        val english: Array<String>
)