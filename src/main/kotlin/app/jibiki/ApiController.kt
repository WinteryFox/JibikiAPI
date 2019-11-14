package app.jibiki

import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@CrossOrigin
@RestController
class ApiController(
        private val database: Database
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/sentence"], produces = ["application/json"])
    fun sentenceSearch(@RequestParam("q") query: String): Flux<Sentence> {
        return database.getSentences(query)
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/word"], produces = ["application/json"])
    fun wordSearch(@RequestParam("q") query: String): Flux<Word> {
        val word = query.replace('*', '%')

        return database.getEntriesForWord(word)
                .flatMap { database.getEntry(it) }
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/kanji"], produces = ["application/json"])
    fun kanjiSearch(@RequestParam("q") query: String): Flux<Kanji> {
        return database.getKanji(query)
    }
}