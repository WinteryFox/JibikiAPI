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
}