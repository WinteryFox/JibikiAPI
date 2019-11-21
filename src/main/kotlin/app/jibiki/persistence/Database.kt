package app.jibiki.persistence

import app.jibiki.model.*
import com.atilika.kuromoji.unidic.Tokenizer
import com.moji4j.MojiConverter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.streams.toList

@Repository
class Database {
    @Autowired
    private lateinit var client: DatabaseClient
    private val tokenizer = Tokenizer()
    private val converter = MojiConverter()

    fun getSentences(query: String): Flux<SentenceBundle> {
        return client
                .execute("""
SELECT s.id,
       s.lang,
       s.sentence,
       array_remove(array_agg(l.translation), NULL) translations
FROM sentences s
         LEFT JOIN plainto_tsquery('japanese', :q) q ON TRUE
         LEFT JOIN ts_rank_cd(s.tsv, q) score ON TRUE
         LEFT JOIN links l ON l.source = s.id
WHERE s.tsv @@ q AND (s.lang = 'jpn' OR s.lang='eng')
GROUP BY s.id, q, score
ORDER BY score DESC
LIMIT 50
                """)
                .bind("q", query)
                .fetch()
                .all()
                .flatMap { row ->
                    getTranslations(row["translations"] as Array<Int>, row["lang"] as String)
                            .collectList()
                            .filter { it.isNotEmpty() }
                            .map {
                                SentenceBundle(
                                        Sentence(
                                                row["id"] as Int,
                                                row["lang"] as String,
                                                row["sentence"] as String
                                        ),
                                        it
                                )
                            }
                }
    }

    fun getTranslations(ids: Array<Int>, sourceLanguage: String): Flux<Sentence> {
        if (ids.isEmpty())
            return Flux.empty()

        return client
                .execute("""
SELECT s.id, s.lang, s.sentence
FROM sentences s
WHERE s.id = ANY (:ids) AND (s.lang = 'jpn' OR s.lang='eng') AND (s.lang != :source)
                """)
                .bind("ids", ids)
                .bind("source", sourceLanguage)
                .map { row ->
                    Sentence(
                            row["id"] as Int,
                            row["lang"] as String,
                            row["sentence"] as String
                    )
                }
                .all()
    }

    fun getKanji(kanji: String): Flux<Kanji> {
        return client
                .execute("""
SELECT character.id,
       character.literal,
       meaning.meaning,
       kunyomi.reading kunyomi,
       onyomi.reading  onyomi,
       misc.grade,
       misc.stroke_count,
       misc.frequency,
       misc.jlpt,
       misc.radical_name
FROM character
         LEFT JOIN (SELECT character, ARRAY_AGG(meaning) meaning
                    FROM meaning
                    WHERE language = 'en'
                    GROUP BY meaning.character) meaning
                   ON character.id = meaning.character
         LEFT JOIN (SELECT character, ARRAY_AGG(reading) reading
                    FROM reading
                    WHERE type = 'ja_kun'
                    GROUP BY character) kunyomi
                   ON character.id = kunyomi.character
         LEFT JOIN (SELECT character, ARRAY_AGG(reading) reading
                    FROM reading
                    WHERE type = 'ja_on'
                    GROUP BY character) onyomi ON character.id = onyomi.character
         LEFT JOIN miscellaneous misc on character.id = misc.character
WHERE literal = :kanji
LIMIT 50
""")
                .bind("kanji", kanji)
                .map { row, _ ->
                    Kanji(
                            row["id"] as Short,
                            row["literal"] as String,
                            row["meaning"] as Array<String>,
                            row["kunyomi"] as Array<String>? ?: emptyArray(),
                            row["onyomi"] as Array<String>? ?: emptyArray(),
                            row["grade"] as Int?,
                            row["stroke_count"] as Int,
                            row["frequency"] as Int?,
                            row["jlpt"] as Int?,
                            row["radical_name"] as String?
                    )
                }
                .all()
    }

    fun getEntriesForWord(word: String): Flux<Int> {
        val exact = !word.contains('*').or(word.contains('＊'))
        val equals = if (exact) "=" else "LIKE"
        val query = word.replace('*', '%').replace('＊', '%')

        return client
                .execute("""
SELECT entries.entr
FROM (SELECT entr, txt
      FROM kanj
      WHERE lower(txt) $equals lower(:q)
      UNION ALL
      SELECT entr, txt
      FROM rdng
      WHERE txt $equals hiragana(:reading)
         OR txt $equals katakana(:reading)
      UNION ALL
      SELECT entr, txt
      FROM gloss
      WHERE lower(txt) $equals lower(:q)) entries
ORDER BY entries.txt
                """)
                .bind("q", query)
                .bind("reading", converter.convertRomajiToHiragana(query))
                .fetch()
                .all()
                .map { row ->
                    row["entr"] as Int
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
        return client.execute("""
SELECT entr.id      entr,
       kanj.txt     kanji,
       kwkinf.descr kanji_info,
       rdng.txt     reading,
       kwrinf.descr reading_info
FROM entr
         LEFT JOIN kanj ON kanj.entr = entr.id
         LEFT JOIN kinf ON kinf.entr = entr.id AND kinf.kanj = kanj.kanj
         LEFT JOIN kwkinf ON kwkinf.id = kinf.kw
         LEFT JOIN rdng ON rdng.entr = entr.id
         LEFT JOIN rinf ON rinf.entr = entr.id AND rinf.rdng = rdng.rdng
         LEFT JOIN kwrinf ON kwrinf.id = rinf.kw
WHERE entr.id = :id
GROUP BY entr.id, kanj.kanj, rdng.rdng, kanj.txt, kwkinf.descr, rdng.txt, kwrinf.descr
ORDER BY kanj.kanj, rdng.rdng
LIMIT 50
        """)
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
        return client.execute("""
SELECT sense.notes,
       pos.pos,
       pos.pos_info,
       fld.fld,
       fld.fld_info,
       gloss.txt  gloss,
       misc.descr misc
FROM sens sense
         LEFT JOIN (SELECT pos.entr, pos.sens, ARRAY_AGG(kwpos.kw) pos, ARRAY_AGG(kwpos.descr) pos_info
                    FROM pos
                             JOIN kwpos ON pos.kw = kwpos.id
                    GROUP BY pos.entr, pos.sens) pos ON pos.entr = sense.entr AND pos.sens = sense.sens
         LEFT JOIN (SELECT fld.entr, fld.sens, ARRAY_AGG(kwfld.kw) fld, ARRAY_AGG(kwfld.descr) fld_info
                    FROM fld
                             JOIN kwfld ON fld.kw = kwfld.id
                    GROUP BY fld.entr, fld.sens) fld ON fld.entr = sense.entr AND fld.sens = sense.sens
         LEFT JOIN (SELECT gloss.entr, gloss.sens, ARRAY_AGG(gloss.txt) txt
                    FROM gloss
                    GROUP BY gloss.entr, gloss.sens) gloss ON gloss.entr = sense.entr AND gloss.sens = sense.sens
         LEFT JOIN (SELECT misc.entr, misc.sens, kwmisc.descr
                    FROM misc
                             LEFT JOIN kwmisc ON misc.kw = kwmisc.id) misc
                   ON misc.entr = sense.entr AND misc.sens = sense.sens
WHERE sense.entr = :entry
                """)
                .bind("entry", entry)
                .map { row, _ ->
                    Sense(
                            row["gloss"] as Array<String>? ?: emptyArray(),
                            (row["pos"] as Array<String>? ?: emptyArray())
                                    .toList()
                                    .zip(row["pos_info"] as Array<String>? ?: emptyArray())
                                    .stream()
                                    .map {
                                        PartOfSpeech(it.first, it.second)
                                    }
                                    .toList()
                                    .toTypedArray(),
                            (row["fld"] as Array<String>? ?: emptyArray())
                                    .toList()
                                    .zip(row["fld_info"] as Array<String>? ?: emptyArray())
                                    .stream()
                                    .map {
                                        FieldOfUse(it.first, it.second)
                                    }
                                    .toList()
                                    .toTypedArray(),
                            row["notes"] as String?,
                            row["misc"] as String?
                    )
                }
                .all()
    }
}