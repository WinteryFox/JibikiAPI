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
    @RequestMapping(method = [RequestMethod.GET], value = ["/all"], produces = ["application/json"])
    fun getAll(
            @RequestParam("query")
            query: String,
            @RequestParam("sentenceCount", defaultValue = "1")
            sentenceCount: Int,
            @RequestParam("page", defaultValue = "0")
            page: Int
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getAll(query, sentenceCount, page)
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/words"], produces = ["application/json"])
    fun getWords(
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
    fun getKanji(
            @RequestParam("query")
            query: String,
            @RequestParam("page", defaultValue = "0")
            page: Int
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getKanji(query, page)
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/sentences"], produces = ["application/json"])
    fun getSentences(
            @RequestParam("query")
            query: String,
            @RequestParam("page", defaultValue = "0")
            page: Int,
            @RequestParam("minLength", defaultValue = "0")
            minLength: Int,
            @RequestParam("maxLength", defaultValue = "10000")
            maxLength: Int,
            @RequestParam("source", defaultValue = "eng")
            source: String
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        return database.getSentences(query, page, minLength, maxLength, source)
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
    ): Mono<ResponseEntity<Void>> {
        return exchange.formData.flatMap {
            database
                    .createToken(it["email"]!![0], it["password"]!![0])
                    .map { token ->
                        ResponseEntity
                                .status(HttpStatus.OK)
                                .header("Set-Cookie", "token=$token; SameSite=Strict; HttpOnly; Secure")
                                .build<Void>()
                    }
                    .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()))
        }
    }

    @RequestMapping(method = [RequestMethod.DELETE], value = ["/users/logout"])
    fun logout(
            @CookieValue("token")
            token: String
    ): Mono<ResponseEntity<Void>> {
        return database
                .deleteToken(token)
                .thenReturn(ResponseEntity.status(HttpStatus.OK).header("Set-Cookie", "token=null; Expires=0; Max-Age=0").build())
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/users/@me"], produces = ["application/json"])
    fun getMe(
            @CookieValue("token")
            token: String?
    ): Mono<ResponseEntity<String>> {
        require(token != null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build<String>())
        }

        return database
                .getSelf(token)
                .map { ResponseEntity.status(HttpStatus.OK).body(it) }
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()))
                .onErrorReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
    }

    @RequestMapping(method = [RequestMethod.PUT], value = ["/users/bookmarks"])
    fun createBookmark(
            @CookieValue("token")
            token: String?,
            @RequestParam("type")
            type: Int,
            @RequestParam("bookmark")
            bookmark: Int
    ): Mono<ResponseEntity<Void>> {
        require(token != null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build<Void>())
        }

        return database
                .createBookmark(token, type, bookmark)
                .filter { it > 0 }
                .map { ResponseEntity.status(HttpStatus.CREATED).build<Void>() }
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()))
    }

    @RequestMapping(method = [RequestMethod.DELETE], value = ["/users/bookmarks"])
    fun deleteBookmark(
            @CookieValue("token")
            token: String?,
            @RequestParam("type")
            type: Int,
            @RequestParam("bookmark")
            bookmark: Int
    ): Mono<ResponseEntity<Void>> {
        require(token != null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build<Void>())
        }

        return database
                .deleteBookmark(token, type, bookmark)
                .filter { it > 0 }
                .map { ResponseEntity.status(HttpStatus.OK).build<Void>() }
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()))
    }
}