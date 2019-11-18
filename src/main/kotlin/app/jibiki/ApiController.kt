package app.jibiki

import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@CrossOrigin
@RestController
class ApiController(
        private val database: Database
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/sentences"], produces = ["application/json"])
    fun sentenceSearch(
            @RequestParam("query") query: String,
            @RequestParam("minLength", defaultValue = "0") minLength: Int,
            @RequestParam("maxLength", defaultValue = "0") maxLength: Int
    ): Flux<SentenceBundle> {
        return database.getSentences(query)
                .filter { it.sentence.sentence.length >= minLength }
                .filter { if (maxLength != 0) it.sentence.sentence.length <= maxLength else true }
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/words"], produces = ["application/json"])
    fun wordSearch(@RequestParam("query") query: String): Flux<Word> {
        return database.getEntriesForWord(query)
                .flatMap { database.getEntry(it) }
    }

    @RequestMapping(method = [RequestMethod.GET], value = ["/kanji"], produces = ["application/json"])
    fun kanjiSearch(@RequestParam("query") query: String): Flux<Kanji> {
        return database.getKanji(query)
    }
}