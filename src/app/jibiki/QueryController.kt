package app.jibiki

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class QueryController {
    @RequestMapping(method=[RequestMethod.GET], value=["/"])
    fun query(@RequestParam(value="q") query: String): String {
        return Query(query);
    }
}