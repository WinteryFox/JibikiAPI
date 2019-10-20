package app.jibiki

import com.moji4j.MojiConverter
import com.moji4j.MojiDetector
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class Database {
    @Autowired
    private lateinit var client: DatabaseClient

    fun getEntriesForWord(word: String): Flux<Int> {
        val detector = MojiDetector()
        val converter = MojiConverter()

        return if (word.contains('%'))
            when {
                detector.hasKanji(word) -> client.execute("SELECT DISTINCT entr FROM kanj WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKana(word) -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                else -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt ILIKE :word LIMIT 50").bind("word", converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                        .switchIfEmpty(client.execute("SELECT DISTINCT entr FROM gloss WHERE txt ILIKE :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all())
            }
        else
            when {
                detector.hasKanji(word) -> client.execute("SELECT DISTINCT entr FROM kanj WHERE txt = :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKana(word) -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt = :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                else -> client.execute("SELECT DISTINCT entr FROM rdng WHERE txt = :word LIMIT 50").bind("word", converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                        .switchIfEmpty(client.execute("SELECT DISTINCT entr FROM gloss WHERE txt = :word LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all())
            }
    }

    fun getEntry(id: Int): Mono<Entry> {
        return getKanjisForEntry(id)
                .collectList()
                .flatMap { kanjis ->
                    getSensesForEntry(id)
                            .collectList()
                            .map {
                                Entry(
                                        id,
                                        kanjis.toTypedArray(),
                                        it.toTypedArray()
                                )
                            }
                }
    }

    fun getKanjisForEntry(entry: Int): Flux<Form> {
        return client.execute("SELECT entr.id      entr,\n" +
                "       kanj.txt     kanji,\n" +
                "       kwkinf.descr kanji_info,\n" +
                "       rdng.txt     reading,\n" +
                "       kwrinf.descr reading_info\n" +
                "FROM entr\n" +
                "         LEFT JOIN kanj ON kanj.entr = entr.id\n" +
                "         LEFT JOIN kinf ON kinf.entr = entr.id AND kinf.kanj = kanj.kanj\n" +
                "         LEFT JOIN kwkinf ON kwkinf.id = kinf.kw\n" +
                "         LEFT JOIN rdng ON rdng.entr = entr.id\n" +
                "         LEFT JOIN rinf ON rinf.entr = entr.id AND rinf.rdng = rdng.rdng\n" +
                "         LEFT JOIN kwrinf ON kwrinf.id = rinf.kw\n" +
                "WHERE entr.id = :id\n" +
                "GROUP BY entr.id, kanj.txt, kwkinf.descr, rdng.txt, kwrinf.descr\n" +
                "LIMIT 50")
                .bind("id", entry)
                .map { row ->
                    Form(
                            row["kanji"] as String?,
                            row["kanji_info"] as String?,
                            row["reading"] as String,
                            row["reading_info"] as String?
                    )
                }
                .all()
    }

    fun getSensesForEntry(entry: Int): Flux<Sense> {
        return client.execute("SELECT s.entr                                               entry,\n" +
                "       s.sens                                               sense,\n" +
                "       s.notes                                              notes,\n" +
                "       ARRAY_REMOVE(ARRAY_AGG(DISTINCT kwpos.kw), NULL)     pos,\n" +
                "       ARRAY_REMOVE(ARRAY_AGG(DISTINCT kwfld.descr), NULL)  fld,\n" +
                "       ARRAY_REMOVE(ARRAY_AGG(DISTINCT kwmisc.descr), NULL) misc,\n" +
                "       ARRAY_AGG(DISTINCT g.txt)                            gloss\n" +
                "FROM sens s\n" +
                "         LEFT JOIN pos p ON p.entr = s.entr AND p.sens = s.sens\n" +
                "         LEFT JOIN kwpos ON kwpos.id = p.kw\n" +
                "         LEFT JOIN fld f ON f.entr = s.entr AND f.sens = s.sens\n" +
                "         LEFT JOIN kwfld ON kwfld.id = f.kw\n" +
                "         LEFT JOIN misc ON misc.entr = s.entr AND misc.sens = s.sens\n" +
                "         LEFT JOIN kwmisc ON kwmisc.id = misc.kw\n" +
                "         LEFT JOIN gloss g ON g.entr = s.entr AND g.sens = s.sens AND g.lang = 1\n" +
                "WHERE s.entr = :entry\n" +
                "GROUP BY s.entr, s.sens\n" +
                "ORDER BY s.sens\n" +
                "LIMIT 50")
                .bind("entry", entry)
                .map { row, _ ->
                    Sense(
                            row["gloss"] as Array<String>,
                            row["pos"] as Array<String>,
                            row["fld"] as Array<String>,
                            row["notes"] as String?,
                            row["misc"] as Array<String>
                    )
                }
                .all()
    }
}