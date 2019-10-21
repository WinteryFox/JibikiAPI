package app.jibiki

data class Entry(
        val id: Int,
        val forms: Array<Form>,
        val senses: Array<Sense>
)