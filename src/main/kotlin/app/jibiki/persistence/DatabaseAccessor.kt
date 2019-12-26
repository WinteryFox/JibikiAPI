package app.jibiki.persistence

import com.moji4j.MojiConverter
import io.r2dbc.postgresql.codec.Json
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

@Repository
class DatabaseAccessor {
    @Autowired
    private lateinit var client: DatabaseClient
    private val converter = MojiConverter()

    private val pageSize = 50

    fun getSentences(query: String, page: Int, minLength: Int, maxLength: Int, source: String, target: String): Mono<String> {
        return client.execute("""
SELECT coalesce(json_agg(json), '[]'::json) json
FROM (SELECT json_build_object(
                     'id', source.id,
                     'language', source.lang,
                     'sentence', source.sentence,
                     'translations', json_agg(
                             json_build_object(
                                     'id', translations.id,
                                     'language', translations.lang,
                                     'sentence', translations.sentence
                                 )
                         )
                 ) json
      FROM links
               JOIN sentences source
                    ON (source.id = links.translation OR source.id = links.source)
                        AND source.lang = :source
               JOIN sentences translations
                    ON (translations.id = links.translation OR translations.id = links.source)
                        AND translations.lang = :target
      WHERE source = ANY (SELECT entries.id entries
                          FROM sentences entries
                          WHERE entries.tsv @@ plainto_tsquery('japanese', :query)
                            AND (entries.lang = 'eng' OR entries.lang = 'jpn')
                            AND length(entries.sentence) > :minLength
                            AND length(entries.sentence) < :maxLength
                          LIMIT :pageSize
                          OFFSET
                          :page * :pageSize)
      GROUP BY source.id) json
        """)
                .bind("pageSize", pageSize)
                .bind("page", page * pageSize)
                .bind("query", query)
                .bind("minLength", minLength)
                .bind("maxLength", maxLength)
                .bind("source", source)
                .bind("target", target)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun getWords(query: String, page: Int): Mono<String> {
        return client.execute("""
SELECT coalesce(json_agg(json_build_object(
        'id', entry.id,
        'forms', forms.json,
        'senses', senses.json
    )), '[]'::json) json
FROM entr entry
         JOIN mv_forms forms
              ON forms.entr = entry.id
         JOIN mv_senses senses
              ON senses.entr = entry.id
WHERE entry.id = ANY (SELECT entries.entr
                      FROM (SELECT entr, txt
                            FROM gloss
                            WHERE regexp_replace(txt, '\s\(.*\)', '') = :query
                            UNION
                            SELECT entr, txt
                            FROM kanj
                            WHERE txt % :query
                            UNION
                            SELECT entr, txt
                            FROM rdng
                            WHERE txt = hiragana(:japanese)
                               OR txt = katakana(:japanese)
                            LIMIT :pageSize
                            OFFSET
                            :page * :pageSize) entries
                      ORDER BY word_similarity(txt, :query) DESC,
                               word_similarity(txt, :japanese) DESC)
  AND src != 3
        """)
                .bind("pageSize", pageSize)
                .bind("page", page)
                .bind("query", query)
                .bind("japanese", converter.convertRomajiToHiragana(query))
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun getKanji(query: String, page: Int): Mono<String> {
        return client.execute("""
SELECT coalesce(json_agg(json_build_object(
        'id', character.id,
        'literal', character.literal,
        'definitions', meaning.json,
        'readings', json_build_object(
                'onyomi', onyomi.json,
                'kunyomi', kunyomi.json
            ),
        'miscellaneous', json_build_object(
                'grade', misc.grade,
                'stroke_count', misc.stroke_count,
                'frequency', misc.frequency,
                'variant_type', misc.variant_type,
                'variant', misc.variant,
                'jlpt', misc.jlpt,
                'radical_name', misc.radical_name
            )
    )), '[]'::json) json
FROM character
         LEFT JOIN (SELECT character, json_agg(meaning) json
                    FROM meaning
                    WHERE language = 'en'
                    GROUP BY character) meaning
                   ON meaning.character = character.id
         LEFT JOIN (SELECT character, json_agg(reading) json
                    FROM reading
                    WHERE type = 'ja_on'
                    GROUP BY character) onyomi
                   ON onyomi.character = character.id
         LEFT JOIN (SELECT character, json_agg(reading) json
                    FROM reading
                    WHERE type = 'ja_kun'
                    GROUP BY character) kunyomi
                   ON kunyomi.character = character.id
         LEFT JOIN (SELECT * FROM miscellaneous) misc on character.id = misc.character
WHERE character.id = ANY (
    SELECT character
    FROM meaning
    WHERE lower(meaning) = lower(:query)
    UNION
    SELECT character
    FROM reading
    WHERE reading = hiragana(:japanese)
       OR reading = katakana(:japanese)
    UNION
    SELECT id
    FROM character
    WHERE literal = :query
)
LIMIT :pageSize
OFFSET
:page * :pageSize
        """)
                .bind("pageSize", pageSize)
                .bind("page", page)
                .bind("query", query)
                .bind("japanese", converter.convertRomajiToHiragana(query))
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }
}