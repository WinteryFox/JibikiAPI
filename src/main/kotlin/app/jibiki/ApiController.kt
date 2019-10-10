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
        val sense = database.query("SELECT sense.entr entry, gloss.gloss, pos.kw, fld.fld\n" +
                "FROM sens sense\n" +
                "         LEFT JOIN (SELECT entr, sens, array_agg((SELECT kw FROM kwpos WHERE id = pos.kw)) kw\n" +
                "                    FROM pos\n" +
                "                    GROUP BY entr, sens) pos ON pos.entr = sense.entr AND pos.sens = sense.sens\n" +
                "         LEFT JOIN (SELECT entr, sens, array_agg((SELECT descr FROM kwfld WHERE id = fld.kw)) fld\n" +
                "                    FROM fld\n" +
                "                    GROUP BY entr, sens) fld ON fld.entr = sense.entr AND fld.sens = sense.sens\n" +
                "         LEFT JOIN (SELECT entr, sens, array_agg(txt) gloss FROM gloss GROUP BY entr, sens) gloss\n" +
                "                   ON gloss.entr = sense.entr AND gloss.sens = sense.sens\n" +
                "WHERE sense.entr = ANY (?);", entry.map { it.get<Long>("id") }.toLongArray())

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