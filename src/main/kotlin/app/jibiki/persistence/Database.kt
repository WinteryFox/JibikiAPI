package app.jibiki.persistence

import app.jibiki.model.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface Database {
    val pageSize: Int
        get() = 50

    fun getSentences(query: String, page: Int): Flux<SentenceBundle>
    fun getTranslations(ids: Array<Int>, sourceLanguage: String): Flux<Sentence>
    fun getKanji(kanji: String): Flux<Kanji>
    fun getEntriesForWord(word: String, page: Int): Flux<Int>
    fun getEntry(id: Int): Mono<Word>
    fun getKanjisForEntry(entry: Int): Flux<Form>
    fun getSensesForEntry(entry: Int): Flux<Sense>
}