package app.jibiki.model

data class Sense(
        val gloss: Array<String> = emptyArray(),
        val pos: Array<PartOfSpeech> = emptyArray(),
        val fld: Array<FieldOfUse> = emptyArray(),
        val notes: String? = null,
        val misc: String? = null
)