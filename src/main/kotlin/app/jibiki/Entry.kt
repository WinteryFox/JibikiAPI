package app.jibiki

data class Entry(
        val kanji: Array<String>?,
        val readings: Array<String>?,
        val sense: Array<Sense>?
)