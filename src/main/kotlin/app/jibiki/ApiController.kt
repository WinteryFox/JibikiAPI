package app.jibiki

import com.moji4j.MojiConverter
import com.moji4j.MojiDetector
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.*

@RestController
class ApiController {
    @RequestMapping(method = [RequestMethod.GET], value = ["/api/v1/word"], produces = ["application/json"])
    fun query(@RequestParam("q") query: String): Array<Entry> {
        val word = query.replace('*', '%')

        val detector = MojiDetector()
        val converter = MojiConverter()
        val entries = when {
            detector.hasKanji(query) -> database.query("SELECT entr FROM kanj WHERE txt ILIKE ?", word)
            detector.hasKana(query) -> database.query("SELECT entr FROM rdng WHERE txt ILIKE ?", word)
            else -> {
                val kana = database.query("SELECT entr FROM rdng WHERE txt ILIKE ?", converter.convertRomajiToHiragana(word))
                if (kana.isNotEmpty())
                    kana
                else
                    database.query("SELECT entr FROM gloss WHERE txt ILIKE ?", word)
            }
        }

        val sense = database.query("SELECT s.entr                                   entry,\n" +
                "       s.sens                                   sense,\n" +
                "       ARRAY_AGG(DISTINCT kwpos.kw)    as pos,\n" +
                "       ARRAY_AGG(DISTINCT kwfld.descr) as fld,\n" +
                "       ARRAY_AGG(g.txt)                as gloss\n" +
                "FROM sens s\n" +
                "         LEFT JOIN pos p ON p.entr = s.entr AND p.sens = s.sens\n" +
                "         LEFT JOIN kwpos ON kwpos.id = p.kw\n" +
                "         LEFT JOIN fld f ON f.entr = s.entr AND f.sens = s.sens\n" +
                "         LEFT JOIN kwfld ON kwfld.id = f.kw\n" +
                "         LEFT JOIN gloss g ON g.entr = s.entr AND g.sens = s.sens\n" +
                "WHERE s.entr = ANY (?)\n" +
                "GROUP BY s.entr, s.sens;", entries.map { it.get<Long>("entr") }.toLongArray())

        return entries.map { row ->
            Entry(
                    row.get<java.sql.Array>("kanji")?.array as Array<String>?,
                    row.get<java.sql.Array>("reading")?.array as Array<String>?,
                    sense
                            .filter { it.get<Int>("entry") == row.get<Int>("entr") }
                            .map {
                                Sense(
                                        it.get<java.sql.Array>("gloss")?.array as Array<String>?,
                                        it.get<java.sql.Array>("pos")?.array as Array<String>?,
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