package app.jibiki.model

data class Kanji(
        val id: Short = 0,
        val literal: String = "",
        val meaning: Array<String>? = null,
        val kunyomi: Array<String>? = null,
        val onyomi: Array<String>? = null,
        val grade: Int? = null,
        val strokeCount: Int = 0,
        val frequency: Int? = null,
        val jlpt: Int? = null,
        val radicalName: String? = null
)