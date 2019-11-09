package app.jibiki

data class Sense(
        val gloss: Array<String>,
        val pos: Array<PartOfSpeech>,
        val fld: Array<FieldOfUse>,
        val notes: String?,
        val misc: String?
)