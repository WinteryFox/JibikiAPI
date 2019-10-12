package app.jibiki

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Flux

@Controller
class SiteController(
        private val database: Database
) {
    @RequestMapping(value = ["/"])
    fun index(model: Model): Flux<Int> {
        return database.getEntriesForWord("hello")
    }
}