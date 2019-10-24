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

    fun getKanji(kanji: String): Mono<Kanji> {
        return client
                .execute("SELECT character.id,\n" +
                        "       character.literal,\n" +
                        "       meaning.meaning,\n" +
                        "       kunyomi.reading kunyomi,\n" +
                        "       onyomi.reading  onyomi,\n" +
                        "       misc.grade,\n" +
                        "       misc.stroke_count,\n" +
                        "       misc.frequency,\n" +
                        "       misc.jlpt,\n" +
                        "       misc.radical_name\n" +
                        "FROM character\n" +
                        "         LEFT JOIN (SELECT character, ARRAY_AGG(meaning) meaning\n" +
                        "                    FROM meaning\n" +
                        "                    WHERE language = 'en'\n" +
                        "                    GROUP BY meaning.character) meaning\n" +
                        "                   ON character.id = meaning.character\n" +
                        "         LEFT JOIN (SELECT character, ARRAY_AGG(reading) reading\n" +
                        "                    FROM reading\n" +
                        "                    WHERE type = 'ja_kun'\n" +
                        "                    GROUP BY character) kunyomi\n" +
                        "                   ON character.id = kunyomi.character\n" +
                        "         LEFT JOIN (SELECT character, ARRAY_AGG(reading) reading\n" +
                        "                    FROM reading\n" +
                        "                    WHERE type = 'ja_on'\n" +
                        "                    GROUP BY character) onyomi ON character.id = onyomi.character\n" +
                        "         LEFT JOIN miscellaneous misc on character.id = misc.character\n" +
                        "WHERE literal = :kanji")
                .bind("kanji", kanji)
                .map { row, _ ->
                    Kanji(
                            row["id"] as Short,
                            row["literal"] as String,
                            row["meaning"] as Array<String>,
                            row["kunyomi"] as Array<String>?,
                            row["onyomi"] as Array<String>?,
                            row["grade"] as Int?,
                            row["stroke_count"] as Int,
                            row["frequency"] as Int?,
                            row["jlpt"] as Int?,
                            row["radical_name"] as String?
                    )
                }
                .first()
    }

    fun getEntriesForWord(word: String): Flux<Int> {
        val detector = MojiDetector()
        val converter = MojiConverter()

        return if (word.contains('%'))
            when {
                word.startsWith('"').and(word.endsWith('"')) -> client.execute("SELECT DISTINCT entr FROM gloss WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", word.replace("\"", "")).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKanji(word) -> client.execute("SELECT DISTINCT entr FROM kanj WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKana(word) -> client.execute("SELECT DISTINCT entr FROM rdng WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                else -> client.execute("SELECT DISTINCT entr FROM rdng WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                        .switchIfEmpty(client.execute("SELECT DISTINCT entr FROM gloss WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all())
            }
        else
            when {
                word.startsWith('"').and(word.endsWith('"')) -> client.execute("SELECT DISTINCT entr FROM gloss WHERE LOWER(txt) ILIKE LOWER(:word) LIMIT 50").bind("word", word.replace("\"", "")).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKanji(word) -> client.execute("SELECT DISTINCT entr FROM kanj WHERE LOWER(txt) = LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                detector.hasKana(word) -> client.execute("SELECT DISTINCT entr FROM rdng WHERE LOWER(txt) = LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all()
                else -> client.execute("SELECT DISTINCT entr FROM rdng WHERE LOWER(txt) = LOWER(:word) LIMIT 50").bind("word", converter.convertRomajiToHiragana(word)).map { row, _ -> row["entr"] as Int }.all()
                        .switchIfEmpty(client.execute("SELECT DISTINCT entr FROM gloss WHERE LOWER(txt) = LOWER(:word) LIMIT 50").bind("word", word).map { row, _ -> row["entr"] as Int }.all())
            }
    }

    fun getEntry(id: Int): Mono<Word> {
        return getKanjisForEntry(id)
                .collectList()
                .flatMap { kanjis ->
                    getSensesForEntry(id)
                            .collectList()
                            .map {
                                Word(
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
                "GROUP BY entr.id, kanj.kanj, rdng.rdng, kanj.txt, kwkinf.descr, rdng.txt, kwrinf.descr\n" +
                "ORDER BY kanj.kanj, rdng.rdng\n" +
                "LIMIT 50")
                .bind("id", entry)
                .map { row ->
                    Form(
                            row["kanji"] as String?,
                            row["kanji_info"] as String?,
                            row["reading"] as String?,
                            row["reading_info"] as String?
                    )
                }
                .all()
    }

    fun getSensesForEntry(entry: Int): Flux<Sense> {
        return client.execute("SELECT sense.notes,\n" +
                "       pos.pos,\n" +
                "       pos.pos_info,\n" +
                "       fld.fld,\n" +
                "       fld.fld_info,\n" +
                "       gloss.txt  gloss,\n" +
                "       misc.descr misc\n" +
                "FROM sens sense\n" +
                "         LEFT JOIN (SELECT pos.entr, pos.sens, ARRAY_AGG(kwpos.kw) pos, ARRAY_AGG(kwpos.descr) pos_info\n" +
                "                    FROM pos\n" +
                "                             JOIN kwpos ON pos.kw = kwpos.id\n" +
                "                    GROUP BY pos.entr, pos.sens) pos ON pos.entr = sense.entr AND pos.sens = sense.sens\n" +
                "         LEFT JOIN (SELECT fld.entr, fld.sens, ARRAY_AGG(kwfld.kw) fld, ARRAY_AGG(kwfld.descr) fld_info\n" +
                "                    FROM fld\n" +
                "                             JOIN kwfld ON fld.kw = kwfld.id\n" +
                "                    GROUP BY fld.entr, fld.sens) fld ON fld.entr = sense.entr AND fld.sens = sense.sens\n" +
                "         LEFT JOIN (SELECT gloss.entr, gloss.sens, ARRAY_AGG(gloss.txt) txt\n" +
                "                    FROM gloss\n" +
                "                    GROUP BY gloss.entr, gloss.sens) gloss ON gloss.entr = sense.entr AND gloss.sens = sense.sens\n" +
                "         LEFT JOIN (SELECT misc.entr, misc.sens, kwmisc.descr\n" +
                "                    FROM misc\n" +
                "                             LEFT JOIN kwmisc ON misc.kw = kwmisc.id) misc\n" +
                "                   ON misc.entr = sense.entr AND misc.sens = sense.sens\n" +
                "WHERE sense.entr = :entry")
                .bind("entry", entry)
                .map { row, _ ->
                    Sense(
                            row["gloss"] as Array<String>? ?: emptyArray(),
                            row["pos"] as Array<String>? ?: emptyArray(),
                            row["pos_info"] as Array<String>? ?: emptyArray(),
                            row["fld"] as Array<String>? ?: emptyArray(),
                            row["fld_info"] as Array<String>? ?: emptyArray(),
                            row["notes"] as String?,
                            row["misc"] as String?
                    )
                }
                .all()
    }
}