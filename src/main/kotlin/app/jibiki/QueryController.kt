package app.jibiki

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.*

@RestController
class QueryController {
    @RequestMapping(method = [RequestMethod.GET], value = ["/"], produces = ["application/json"])
    fun query(@RequestParam(value = "q") query: String): Array<Entry> {
        return database.query("SELECT kanj.txt           kanji,\n" +
                "       tforms.forms       forms,\n" +
                "       tglossary.glossary glossary\n" +
                "FROM kanj\n" +
                "         JOIN (SELECT entr, array_agg(rdng), array_agg(txt ORDER BY rdng) AS forms FROM rdng GROUP BY 1) tforms\n" +
                "              ON tforms.entr = kanj.entr\n" +
                "         JOIN (SELECT entr, array_agg(txt) AS glossary FROM gloss GROUP BY 1) tglossary ON tglossary.entr = kanj.entr\n" +
                "WHERE (SELECT src FROM entr WHERE id = kanj.entr) = 1\n" +
                "  AND (kanj.txt LIKE ? OR lower(?) ILIKE ANY(glossary))\n" +
                "GROUP BY kanji, tforms.forms, tglossary.glossary;", query.replace('*', '%'), query.replace('*', '%'))
                .map { Entry(it.get("kanji"), it.get<java.sql.Array>("forms").array as Array<String>, it.get<java.sql.Array>("glossary").array as Array<String>) }
                .toTypedArray()
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleError(e: MissingServletRequestParameterException) = e.message
}