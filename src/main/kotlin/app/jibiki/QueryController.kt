package app.jibiki

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.*

@RestController
class QueryController {
    @RequestMapping(method = [RequestMethod.GET], value = ["/"], produces = ["application/json"])
    fun query(@RequestParam(value = "q") query: String): Array<Entry> {
        return queryJapanese(query)
    }

    fun queryEnglish(query: String): Array<Entry> {
        val response = mutableListOf<Entry>()

        val kanji = database.query("SELECT entr, txt FROM kanj WHERE entr IN (SELECT entr FROM gloss WHERE txt LIKE ?);", query.replace('*', '%'))

        //for (k in kanji)
        //    response.add(Entry(k.get("txt"), database.query("SELECT txt FROM gloss WHERE lang = 1 AND entr = ?", k.get("entr")).map { it.get<String>("txt") }.toTypedArray()))

        return response.toTypedArray()
    }

    fun queryJapanese(query: String): Array<Entry> {
        val response = mutableListOf<Entry>()

        val kanji = database.query("SELECT kanj.entr entry, kanj.txt kanji, rdng.txt reading from kanj inner join rdng using (entr) WHERE (SELECT src FROM entr WHERE id = entr) = 1 AND (kanj.txt LIKE ? OR rdng.txt LIKE ?)", query.replace('*', '%'), query.replace('*', '%'))
        for (k in kanji)
            response.add(Entry(k.get("kanji"), k.get("reading"), database.query("SELECT txt FROM gloss WHERE lang = 1 AND entr = ?", k.get("entry")).map { it.get<String>("txt") }.toTypedArray()))

        return response.toTypedArray()
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleError(e: MissingServletRequestParameterException) = e.message
}