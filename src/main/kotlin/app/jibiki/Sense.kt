package app.jibiki

data class Sense(
        val gloss: Array<String>,
        val pos: Array<String>,
        val pos_info: Array<String>,
        val fld: Array<String>,
        val notes: String?,
        val misc: String?
)