package app.jibiki

import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
class ApiController(
        private val database: Database
) {
    @RequestMapping(method = [RequestMethod.GET], value = ["/api/v1/word"], produces = ["application/json"])
    fun query(@RequestParam("q") query: String): Flux<Entry> {
        val word = query.replace('*', '%')

        database.getEntries(word)
                .map {
                    println(it)
                }.subscribe()
        return Flux.empty()
        /*.flatMapMany { entries ->
database.query("SELECT kanj.entr, kanj.txt kanji, ARRAY_AGG(rdng.txt) readings\n" +
        "FROM kanj\n" +
        "         LEFT JOIN rdng ON rdng.entr = kanj.entr\n" +
        "WHERE kanj.entr = ANY (?)\n" +
        "GROUP BY kanj.entr, kanj.txt LIMIT 50;", entries.toLongArray())
}*/

        /*val sense = database.query("SELECT s.entr                                   entry,\n" +
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
            "GROUP BY s.entr, s.sens LIMIT 50;", entries.map { it.get<Long>("entr") }.toLongArray())

    return kanji.map { row ->
        Entry(
                row.get("entr"),
                row.get("kanji"),
                row.get<java.sql.Array>("readings")?.array as Array<String>?,
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
    }.toTypedArray()*/
    }
}