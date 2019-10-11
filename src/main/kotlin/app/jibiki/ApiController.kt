package app.jibiki

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.*

@RestController
class ApiController {
    @RequestMapping(method = [RequestMethod.GET], value = ["/api/v1/word/{word}"], produces = ["application/json"])
    fun query(@PathVariable("word") query: String): Array<Entry> {
        val word = query.replace('*', '%')

        val entry = database.query("SELECT id, kanji.kanji, reading.reading\n" +
                "FROM entr\n" +
                "         LEFT JOIN (SELECT entr, array_agg(txt ORDER BY kanj) kanji FROM kanj GROUP BY entr) kanji\n" +
                "                   ON kanji.entr = entr.id\n" +
                "         LEFT JOIN (SELECT entr, array_agg(txt ORDER BY rdng) reading FROM rdng GROUP BY entr) reading\n" +
                "                   ON reading.entr = entr.id\n" +
                "WHERE src = 1\n" +
                "  AND (id = ANY (SELECT entr FROM rdng WHERE txt ILIKE ?)\n" +
                "    OR id = ANY (SELECT entr FROM kanj WHERE txt ILIKE ?)\n" +
                "    OR id = ANY (SELECT entr FROM gloss WHERE txt ILIKE ?));", word, word, word)
        val sense = database.query("SELECT s.entr                                   entry,\n" +
                "       s.sens                                   sense,\n" +
                "       STRING_AGG(DISTINCT kwpos.kw, ',')    as pos,\n" +
                "       STRING_AGG(DISTINCT kwfld.descr, ',') as fld,\n" +
                "       STRING_AGG(g.txt, ',')                as gloss\n" +
                "FROM sens s\n" +
                "         LEFT JOIN pos p ON p.entr = s.entr AND p.sens = s.sens\n" +
                "         LEFT JOIN kwpos ON kwpos.id = p.kw\n" +
                "         LEFT JOIN fld f ON f.entr = s.entr AND f.sens = s.sens\n" +
                "         LEFT JOIN kwfld ON kwfld.id = f.kw\n" +
                "         LEFT JOIN gloss g ON g.entr = s.entr AND g.sens = s.sens\n" +
                "WHERE s.entr = ANY (?)\n" +
                "GROUP BY s.entr, s.sens;", entry.map { it.get<Long>("id") }.toLongArray())

        return entry.map { row ->
            Entry(
                    row.get<java.sql.Array>("kanji")?.array as Array<String>?,
                    row.get<java.sql.Array>("reading")?.array as Array<String>?,
                    sense
                            .filter { it.get<Int>("entry") == row.get<Int>("id") }
                            .map {
                                Sense(
                                    it.get<java.sql.Array>("gloss")?.array as Array<String>?,
                                    it.get<java.sql.Array>("kw")?.array as Array<String>?,
                                    it.get<java.sql.Array>("fld")?.array as Array<String>?
                                )
                            }
                            .toTypedArray())
        }.toTypedArray()
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleError(e: MissingServletRequestParameterException) = e.message
}