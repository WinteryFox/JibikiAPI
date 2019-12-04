package app.jibiki.persistence

import app.jibiki.model.*
import app.jibiki.spec.CreateUserSpec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.lettuce.core.RedisClient
import org.reactivestreams.Publisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Function

@Service
class CachingDatabaseAccessor(
        private val database: SqlDatabaseAccessor
) : Database {
    private val redis = RedisClient.create("redis://localhost:6379").connect().reactive() // todo: config smh
    private val mapper = ObjectMapper().registerModule(KotlinModule()).registerModule(JavaTimeModule())

    private fun <O> getRedisObject(
            functionKey: String,
            redisKey: String,
            page: Int,
            `class`: Class<O>
    ): Flux<O> {
        return redis.get(functionKey + "_" + redisKey + "_" + page)
                .flatMapMany { Flux.fromStream(it.split(REDIS_DELIMITER).stream()) }
                .filter { it.isNotEmpty() }
                .map { mapper.readValue(it, `class`) }
    }

    private fun <O, K> getRedisObjectOrCache(
            functionKey: String,
            redisKey: String,
            page: Int,
            originalKey: K,
            `class`: Class<O>,
            dbSupplier: Function<K, Flux<O>>
    ): Publisher<O> {
        return getRedisObject(
                functionKey,
                redisKey,
                page,
                `class`
        ).switchIfEmpty(insertAndReturnOriginal(functionKey, redisKey, page, dbSupplier.apply(originalKey)))
    }

    private fun <O> insertAndReturnOriginal(functionKey: String, redisKey: String, page: Int, original: Flux<O>): Flux<O> {
        val cache = original.cache()
        return cache
                .map { mapper.writeValueAsString(it) }
                .collectList()
                .map { it.joinToString(REDIS_DELIMITER) }
                .flatMap { redis.set(functionKey + "_" + redisKey + "_" + page, it) }
                .flatMap { redis.expire(functionKey + "_" + redisKey + "_" + page, CACHING_TIME) }
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

    private fun deleteKey(
            functionKey: String,
            redisKey: String,
            page: Int
    ): Mono<Long> {
        return redis.del(functionKey + "_" + redisKey + "_" + page)
    }

    override fun getSentences(query: String, page: Int): Mono<JsonNode> {
        return getRedisObjectOrCache(
                "sentences",
                query.toLowerCase(),
                page,
                query,
                JsonNode::class.java,
                Function {
                    database.getSentences(it, page)
                }
        )
    }

    override fun getKanji(kanji: String): Flux<Kanji> {
        return getRedisObjectOrCache(
                "kanji",
                kanji.toLowerCase(),
                0,
                kanji,
                Kanji::class.java,
                Function { database.getKanji(it) }
        )
    }

    override fun getEntriesForWord(word: String, page: Int): Flux<Int> {
        return getRedisObjectOrCache(
                "wordentries",
                word.toLowerCase(),
                page,
                word,
                WordEntry::class.java,
                Function { database.getEntriesForWord(word, page).map { WordEntry(it) } }
        ).map { it.id }
    }

    override fun getEntry(id: Int): Mono<Word> {
        return redis.get("entry_$id")
                .filter { it.isNotEmpty() }
                .map { mapper.readValue(it, Word::class.java) }
                .switchIfEmpty(insertAndReturnOriginal("entry_$id", database.getEntry(id)))
    }

    override fun getKanjisForEntry(entry: Int): Flux<Form> {
        return getRedisObjectOrCache(
                "kanjientry",
                entry.toString(),
                0,
                entry,
                Form::class.java,
                Function { database.getKanjisForEntry(it) }
        )
    }

    override fun getSensesForEntry(entry: Int): Flux<Sense> {
        return getRedisObjectOrCache(
                "senses",
                entry.toString(),
                0,
                entry,
                Sense::class.java,
                Function { database.getSensesForEntry(it) }
        )
    }

    override fun createUser(createUserSpec: CreateUserSpec): Mono<HttpStatus> {
        return database.userExists(createUserSpec.email)
                .flatMap {
                    if (it)
                        Mono.just(HttpStatus.CONFLICT)
                    else
                        database
                                .createUser(createUserSpec)
                }
    }

    override fun checkCredentials(email: String, password: String): Mono<User> {
        return database.checkCredentials(email, password)
    }

    override fun getUser(snowflake: String): Mono<User> {
        return getRedisObjectOrCache(
                "users",
                snowflake,
                0,
                snowflake,
                User::class.java,
                Function { Flux.from(database.getUser(snowflake)) }
        ).next()
    }

    fun invalidateToken(token: String): Mono<Void> {
        return getRedisObject("tokens", token, 0, Token::class.java)
                .next()
                .flatMap {
                    deleteKey("tokens", it.token!!, 0)
                            .flatMap { _ ->
                                deleteKey("tokens", it.snowflake!!, 0)
                            }
                }
                .then()
    }

    fun getToken(user: User): Mono<Token> {
        val token = Token(user.snowflake, String(Base64.getEncoder().encode(UUID.randomUUID().toString().toByteArray())), 600000)

        return getRedisObject("tokens", user.snowflake.toString(), 0, Token::class.java)
                .next()
                .switchIfEmpty(
                        insertAndReturnOriginal("tokens", user.snowflake.toString(), 0, Flux.just(token))
                                .next()
                                .flatMap {
                                    insertAndReturnOriginal("tokens", token.token!!, 0, Flux.just(token))
                                            .next()
                                }
                )
    }

    fun checkToken(token: String): Mono<Token> {
        return getRedisObject("tokens", token, 0, Token::class.java)
                .next()
    }

    data class TranslationKey(val ids: Array<Int>, val sourceLanguage: String)
    data class WordEntry(val id: Int = 0)

    companion object {
        private const val REDIS_DELIMITER = "#*#~#*#" // Why? because I can
        private val CACHING_TIME = TimeUnit.DAYS.toSeconds(7)
    }
}