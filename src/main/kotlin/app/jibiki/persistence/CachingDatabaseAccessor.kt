package app.jibiki.persistence

import app.jibiki.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.RedisClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit
import java.util.function.Function

@Service
class CachingDatabaseAccessor(private val database: SqlDatabaseAccessor) : Database {

    private val redis = RedisClient.create("redis://localhost:6379").connect().reactive() // todo: config smh
    private val mapper = ObjectMapper()

    private fun <O, K> getRedisObjectOrCache(
            functionKey: String,
            redisKey: String,
            originalKey: K,
            `class`: Class<O>,
            dbSupplier: Function<K, Flux<O>>): Flux<O> {
        return redis.get(functionKey + "_" + redisKey)
                .switchIfEmpty(Mono.just(""))
                .flatMapMany { Flux.fromStream(it.split(REDIS_DELIMITER).stream()) }
                .filter { it.isNotEmpty() }
                .map { mapper.readValue(it, `class`) }
                .switchIfEmpty(insertAndReturnOriginal(functionKey, redisKey, dbSupplier.apply(originalKey)))
    }

    private fun <O> insertAndReturnOriginal(functionKey: String, redisKey: String, original: Flux<O>): Flux<O> {
        val cache = original.cache()
        return cache
                .map { mapper.writeValueAsString(it) }
                .collectList()
                .map { it.joinToString(REDIS_DELIMITER) }
                .flatMap { redis.set(functionKey + "_" + redisKey, it) }
                .flatMap { redis.expire(functionKey + "_" + redisKey, CACHING_TIME) }
                .thenMany(cache)
    }

    private fun <O> insertAndReturnOriginal(redisKey: String, original: Mono<O>): Mono<O> {
        val cache = original.cache()
        return cache
                .map { mapper.writeValueAsString(it) }
                .flatMap { redis.set(redisKey, it) }
                .flatMap { redis.expire(redisKey, CACHING_TIME) }
                .then(cache)
    }

    override fun getSentences(query: String): Flux<SentenceBundle> {
        return getRedisObjectOrCache(
                "sentences",
                query.toLowerCase(),
                query,
                SentenceBundle::class.java,
                Function { database.getSentences(it) }
        )
    }

    override fun getTranslations(ids: Array<Int>, sourceLanguage: String): Flux<Sentence> {
        val redisKey = sourceLanguage + "_" + ids.joinToString("_")
        return getRedisObjectOrCache(
                "translations",
                redisKey.toLowerCase(),
                TranslationKey(ids, sourceLanguage),
                Sentence::class.java,
                Function { database.getTranslations(it.ids, it.sourceLanguage) }
        )
    }

    override fun getKanji(kanji: String): Flux<Kanji> {
        return getRedisObjectOrCache(
                "kanji",
                kanji.toLowerCase(),
                kanji,
                Kanji::class.java,
                Function { database.getKanji(it) }
        )
    }

    override fun getEntriesForWord(word: String): Flux<Int> {
        return getRedisObjectOrCache(
                "wordentries",
                word.toLowerCase(),
                word,
                WordEntry::class.java,
                Function { database.getEntriesForWord(word).map { WordEntry(it) } }
        ).map { it.id }
    }

    override fun getEntry(id: Int): Mono<Word> {
        return redis.get("entry_$id")
                .filter { it.isNotEmpty() }
                .switchIfEmpty(insertAndReturnOriginal("entry_$id", Mono.just(mapper.writeValueAsString(database.getEntry(id)))))
                .map { mapper.readValue(it, Word::class.java) }
    }

    override fun getKanjisForEntry(entry: Int): Flux<Form> {
        return getRedisObjectOrCache(
                "kanjientry",
                entry.toString(),
                entry,
                Form::class.java,
                Function { database.getKanjisForEntry(it) }
        )
    }

    override fun getSensesForEntry(entry: Int): Flux<Sense> {
        return getRedisObjectOrCache(
                "senses",
                entry.toString(),
                entry,
                Sense::class.java,
                Function { database.getSensesForEntry(it) }
        )
    }

    data class TranslationKey(val ids: Array<Int>, val sourceLanguage: String)
    data class WordEntry(val id: Int)

    companion object {
        private const val REDIS_DELIMITER = "#*#~#*#" // Why? because I can
        private val CACHING_TIME = TimeUnit.MINUTES.toSeconds(10)
    }
}