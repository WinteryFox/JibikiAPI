package app.jibiki

data class SentenceBundle(
        val sentence: Sentence,
        val translations: List<Sentence>
)