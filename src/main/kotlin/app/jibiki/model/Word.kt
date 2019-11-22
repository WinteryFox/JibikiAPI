package app.jibiki.model

data class Word(
        val id: Int = 0,
        val forms: Array<Form> = emptyArray(),
        val senses: Array<Sense> = emptyArray()
)