package app.jibiki.controller

import app.jibiki.persistence.DatabaseAccessor
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@CrossOrigin(allowCredentials = "true")
@RestController
class ApiController(
        private val database: DatabaseAccessor
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/sentences"], produces = ["application/json"])
    fun sentenceSearch(
            @RequestParam("query")
            query: String,
            @RequestParam("page", defaultValue = "0")
            page: Int,
            @RequestParam("minLength", defaultValue = "0")
            minLength: Int,
            @RequestParam("maxLength", defaultValue = "10000")
            maxLength: Int,
            @RequestParam("source", defaultValue = "eng")
            source: String,
            @RequestParam("target", defaultValue = "jpn")
            target: String
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getSentences(query, page, minLength, maxLength, source, target)
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/words"], produces = ["application/json"])
    fun wordSearch(
            @RequestParam("query")
            query: String,
            @RequestParam("page", defaultValue = "0")
            page: Int
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getWords(query, page)
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/kanji"], produces = ["application/json"])
    fun kanjiSearch(
            @RequestParam("query")
            query: String,
            @RequestParam("page", defaultValue = "0")
            page: Int
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getKanji(query, page)
    }

    @RequestMapping(method = [RequestMethod.POST], value = ["/users/create"], consumes = ["application/x-www-form-urlencoded"])
    fun createUser(
            exchange: ServerWebExchange
    ): Mono<ResponseEntity<String>> {
        return exchange.formData.flatMap {
            database
                    .createUser(it["username"]!![0], it["email"]!![0], it["password"]!![0])
                    .map { json -> ResponseEntity.status(HttpStatus.CREATED).body(json) }
                    .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).build()))
        }
    }

    @RequestMapping(method = [RequestMethod.POST], value = ["/users/login"], consumes = ["application/x-www-form-urlencoded"])
    fun loginUser(
            exchange: ServerWebExchange
    ): Mono<ResponseEntity<String>> {
        return exchange.formData.flatMap {
            database
                    .createToken(it["email"]!![0], it["password"]!![0])
                    .map { token ->
                        ResponseEntity
                                .status(HttpStatus.CREATED)
                                .header("Set-Cookie", "token=$token; SameSite=Strict; HttpOnly; Secure")
                                .build<String>()
                    }
                    .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build()))
        }
    }

    @RequestMapping(method = [RequestMethod.DELETE], value = ["/users/logout"])
    fun logout(
            @CookieValue("token")
            token: String
    ): Mono<ResponseEntity<Void>> {
        return database
                .deleteToken(token)
                .thenReturn(ResponseEntity.noContent().header("Set-Cookie", "token=null; Expires=0; Max-Age=0").build())
    }

    /*@RequestMapping(method = [RequestMethod.GET], value = ["/users/@me"], produces = ["application/json"])
    fun getMe(
            @CookieValue("token") token: String
    ): Mono<User> {
        return database
                .checkToken(token)
                .switchIfEmpty(Mono.error(IllegalArgumentException("Invalid or expired token")))
                .flatMap { database.getUser(it.snowflake!!) }
    }*/
}