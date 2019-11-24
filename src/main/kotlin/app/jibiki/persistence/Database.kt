package app.jibiki.persistence

import app.jibiki.model.*
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

interface Database {
    val pageSize: Int
        get() = 50

    fun getSentences(query: String, page: Int): Publisher<SentenceBundle>
    fun getTranslations(ids: Array<Int>, sourceLanguage: String): Publisher<Sentence>
    fun getKanji(kanji: String): Publisher<Kanji>
    fun getEntriesForWord(word: String, page: Int): Publisher<Int>
    fun getEntry(id: Int): Mono<Word>
    fun getKanjisForEntry(entry: Int): Publisher<Form>
    fun getSensesForEntry(entry: Int): Publisher<Sense>
}