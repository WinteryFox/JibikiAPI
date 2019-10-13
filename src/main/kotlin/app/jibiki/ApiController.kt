package app.jibiki

import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
class ApiController(
        private val database: Database
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/word"], produces = ["application/json"])
    fun wordSearch(@RequestParam("q") query: String): Flux<Entry> {
        val word = query.replace('*', '%')

        return database.getEntriesForWord(word)
                .collectList()
                .flatMapMany { database.getEntries(it) }
    }
}