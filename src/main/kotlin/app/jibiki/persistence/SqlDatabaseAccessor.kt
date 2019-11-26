package app.jibiki.persistence

import app.jibiki.model.*
import app.jibiki.spec.CreateUserSpec
import com.moji4j.MojiConverter
import org.mindrot.jbcrypt.BCrypt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.streams.toList

@Repository
class SqlDatabaseAccessor : Database {
    @Autowired
    private lateinit var client: DatabaseClient
    private val converter = MojiConverter()

    override fun getSentences(query: String, page: Int): Flux<SentenceBundle> {
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
LIMIT :pageSize OFFSET :page
                """)
                .bind("pageSize", pageSize)
                .bind("page", page * pageSize)
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

    override fun getTranslations(ids: Array<Int>, sourceLanguage: String): Flux<Sentence> {
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

    override fun getKanji(kanji: String): Flux<Kanji> {
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
WHERE literal = :kanji OR lower(:kanji) = ANY(meaning.meaning) OR hiragana(:kanji) = ANY(kunyomi.reading) OR katakana(:kanji) = ANY(onyomi.reading)
LIMIT :pageSize
""")
                .bind("pageSize", pageSize)
                .bind("kanji", kanji)
                .map { row, _ ->
                    Kanji(
                            row["id"] as Short,
                            row["literal"] as String,
                            row["meaning"] as Array<String>? ?: emptyArray(),
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

    override fun getEntriesForWord(word: String, page: Int): Flux<Int> {
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
         JOIN entr ON entr.id = entries.entr
WHERE entr.src != 3
ORDER BY entries.txt
LIMIT :pageSize OFFSET :page
                """)
                .bind("pageSize", pageSize)
                .bind("page", page * pageSize)
                .bind("q", query)
                .bind("reading", converter.convertRomajiToHiragana(query))
                .fetch()
                .all()
                .map { row ->
                    row["entr"] as Int
                }
    }

    override fun getEntry(id: Int): Mono<Word> {
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

    override fun getKanjisForEntry(entry: Int): Flux<Form> {
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
WHERE entr.id = :id AND entr.src != 3
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

    override fun getSensesForEntry(entry: Int): Flux<Sense> {
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

    fun userExists(email: String): Mono<Boolean> {
        return client.execute("""
                    SELECT count(*) FROM users WHERE email = :email
                """)
                .bind("email", email)
                .map { row ->
                    row["count"] as Long > 0
                }
                .first()
    }

    override fun createUser(createUserSpec: CreateUserSpec): Mono<HttpStatus> {
        val snowflake = Snowflake.next()
        val salt = BCrypt.gensalt()
        val hash = BCrypt.hashpw(createUserSpec.password, salt)

        return client.execute("INSERT INTO users (snowflake, email, hash, username) VALUES (:snowflake, :email, :hash, :username)")
                .bind("snowflake", snowflake.id)
                .bind("email", createUserSpec.email)
                .bind("hash", hash)
                .bind("username", createUserSpec.username)
                .fetch()
                .first()
                .thenReturn(HttpStatus.CREATED)
    }

    override fun checkCredentials(email: String, password: String): Mono<Boolean> {
        return client.execute("SELECT hash FROM users WHERE email = :email")
                .bind("email", email)
                .fetch()
                .first()
                .map {
                    BCrypt.checkpw(password, it["hash"] as String)
                }
                .switchIfEmpty(Mono.just(false))
    }
}