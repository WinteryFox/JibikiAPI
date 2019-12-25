package app.jibiki.controller

import app.jibiki.persistence.DatabaseAccessor
import org.springframework.beans.BeanInstantiationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
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
            @RequestParam("query") query: String
    ): Mono<String> {
        if (query.isEmpty())
            return Mono.just("[]")

        //return database.getKanji(query)
        return Mono.empty()
    }

    /*@RequestMapping(method = [RequestMethod.POST], value = ["/users/create"], consumes = ["application/x-www-form-urlencoded"])
    fun createUser(
            createUserSpec: CreateUserSpec
    ): Mono<ResponseEntity<HttpStatus>> {
        return database
                .createUser(createUserSpec)
                .map { ResponseEntity<HttpStatus>(it) }
    }

    @RequestMapping(method = [RequestMethod.POST], value = ["/users/login"], consumes = ["application/x-www-form-urlencoded"])
    fun loginUser(
            loginSpec: LoginSpec
    ): Mono<ResponseEntity<Void>> {
        return database
                .checkCredentials(loginSpec.email, loginSpec.password)
                .flatMap { database.getToken(it) }
                .map { ResponseEntity.noContent().header("Set-Cookie", "token=${it.token}; Expires=${it.expiry}; Max-Age=${it.expiry}; SameSite=Strict; HttpOnly; Secure").build<Void>() }
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()))
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/users/@me"], produces = ["application/json"])
    fun getMe(
            @CookieValue("token") token: String
    ): Mono<User> {
        return database
                .checkToken(token)
                .switchIfEmpty(Mono.error(IllegalArgumentException("Invalid or expired token")))
                .flatMap { database.getUser(it.snowflake!!) }
    }

    @RequestMapping(method = [RequestMethod.DELETE], value = ["/users/logout"])
    fun logout(
            @CookieValue("token") token: String
    ): Mono<ResponseEntity<Void>> {
        return database
                .invalidateToken(token)
                .thenReturn(ResponseEntity.noContent().header("Set-Cookie", "token=null; Expires=0; Max-Age=0").build())
    }*/

    @ExceptionHandler(BeanInstantiationException::class)
    fun handleBeans(): ResponseEntity<String> {
        return ResponseEntity.badRequest().build()
    }
}