package app.jibiki

import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@CrossOrigin
@RestController
class ApiController(
        private val database: Database
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/word"], produces = ["application/json"])
    fun wordSearch(@RequestParam("q") query: String): Flux<Word> {
        val word = query.replace('*', '%')

        return database.getEntriesForWord(word)
                .flatMap { database.getEntry(it) }
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/kanji"], produces = ["application/json"])
    fun kanjiSearch(@RequestParam("q") query: String): Mono<Kanji> {
        return database.getKanji(query)
    }
}