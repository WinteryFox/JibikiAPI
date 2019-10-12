package app.jibiki

import com.moji4j.MojiConverter
import com.moji4j.MojiDetector
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
class Database {
    @Autowired
    private lateinit var client: DatabaseClient

    fun getEntriesForWord(word: String): Flux<Int> {
        val detector = MojiDetector()
        val converter = MojiConverter()

        return when {
            detector.hasKanji(word) -> client.execute("SELECT DISTINCT entr FROM kanj WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
            detector.hasKana(word) -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
            else -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE :word LIMIT 50").bind("word", converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                    .switchIfEmpty(client.execute("SELECT DISTINCT entr FROM gloss WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all())
        }
    }

    fun getEntries(ids: List<Int>): Flux<Entry> {
        return client.execute("SELECT kanj.entr, kanj.txt kanji, ARRAY_AGG(rdng.txt) readings\n" +
                "FROM kanj\n" +
                "         LEFT JOIN rdng ON rdng.entr = kanj.entr\n" +
                "WHERE kanj.entr = ANY (:ids)\n" +
                "GROUP BY kanj.entr, kanj.txt\n" +
                "LIMIT 50")
                .bind("ids", ids.toTypedArray())
                .fetch()
                .all()
                .flatMap { row ->
                    getSenses(row["entr"] as Int)
                            .collectList()
                            .map {
                                Entry(
                                        row["entr"] as Int,
                                        row["kanji"] as String,
                                        row["readings"] as Array<String>,
                                        it.toTypedArray()
                                )
                            }
                }
    }

    fun getSenses(entry: Int): Flux<Sense> {
        return client.execute("SELECT s.entr                             entry,\n" +
                "       s.sens                             sense,\n" +
                "       ARRAY_AGG(DISTINCT kwpos.kw)    as pos,\n" +
                "       ARRAY_AGG(DISTINCT kwfld.descr) as fld,\n" +
                "       ARRAY_AGG(g.txt)                as gloss\n" +
                "FROM sens s\n" +
                "         LEFT JOIN pos p ON p.entr = s.entr AND p.sens = s.sens\n" +
                "         LEFT JOIN kwpos ON kwpos.id = p.kw\n" +
                "         LEFT JOIN fld f ON f.entr = s.entr AND f.sens = s.sens\n" +
                "         LEFT JOIN kwfld ON kwfld.id = f.kw\n" +
                "         LEFT JOIN gloss g ON g.entr = s.entr AND g.sens = s.sens\n" +
                "WHERE s.entr = :entry\n" +
                "GROUP BY s.entr, s.sens\n" +
                "LIMIT 50")
                .bind("entry", entry)
                .map { row, _ ->
                    Sense(
                            row["gloss"] as Array<String>,
                            row["pos"] as Array<String>,
                            row["fld"] as Array<String>
                    )
                }
                .all()
    }
}