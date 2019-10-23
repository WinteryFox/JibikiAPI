package app.jibiki

data class Word(
        val id: Int,
        val forms: Array<Form>,
        val senses: Array<Sense>
)