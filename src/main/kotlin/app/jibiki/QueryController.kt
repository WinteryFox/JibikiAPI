package app.jibiki

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.*

@RestController
class QueryController {
    @RequestMapping(method = [RequestMethod.GET], value = ["/"], produces = ["application/json"])
    fun query(@RequestParam(value = "q") query: String): Array<Entry> {
        return database.query("SELECT kanj.entr entry, kanj.txt kanji, treading.txt reading, array_agg(tforms.txt) forms, array_agg(tglossary.txt) glossary FROM kanj\n" +
                "    INNER JOIN rdng treading ON (kanj.entr = treading.entr AND treading.rdng = 1)\n" +
                "    LEFT JOIN gloss tglossary ON (kanj.entr = tglossary.entr AND lang = 1)\n" +
                "    LEFT JOIN rdng tforms ON (kanj.entr = tforms.entr AND tforms.rdng > 1)\n" +
                "WHERE (SELECT src FROM entr WHERE id = kanj.entr) = 1 AND (kanj.txt LIKE ? OR tglossary.txt LIKE ?)\n" +
                "GROUP BY entry, kanji, reading", query.replace('*', '%'), query.replace('*', '%'))
                .map { Entry(it.get("kanji"), it.get("reading"), it.get<java.sql.Array>("forms").array as Array<String?>, it.get<java.sql.Array>("glossary").array as Array<String?>) }
                .toTypedArray()
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleError(e: MissingServletRequestParameterException) = e.message
}