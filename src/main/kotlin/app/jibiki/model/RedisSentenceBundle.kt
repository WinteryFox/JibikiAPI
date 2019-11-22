package app.jibiki.model

data class RedisSentenceBundle(
        val sentence: Sentence = Sentence(),
        val translations: List<Sentence> = mutableListOf()
)