package app.jibiki

data class Kanji(
        val id: Short,
        val literal: String,
        val meaning: Array<String>,
        val kunyomi: Array<String>,
        val onyomi: Array<String>,
        val grade: Int?,
        val strokeCount: Int,
        val frequency: Int?,
        val jlpt: Int?,
        val radicalName: String?
)