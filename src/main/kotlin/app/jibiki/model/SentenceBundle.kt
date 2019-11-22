package app.jibiki.model

data class SentenceBundle(
        val sentence: Sentence = Sentence(),
        val translations: List<Sentence> = mutableListOf()
)