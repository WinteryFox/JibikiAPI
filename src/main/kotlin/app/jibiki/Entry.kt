package app.jibiki

data class Entry(
        val id: Long,
        val kanji: String,
        val readings: Array<String>?,
        val senses: Array<Sense>?
)