package app.jibiki.persistence

import app.jibiki.model.Snowflake
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

    fun getAll(query: String, page: Int): Mono<String> {
        return client.execute("""
SELECT coalesce(json_agg(json.json), '[]'::json) json
FROM (SELECT json_build_object(
                     'word', json_build_object(
                'id', entry.id,
                'forms', forms.json,
                'senses', senses.json
            ),
                     'sentence', example.json,
                     'kanji', coalesce(array_to_json(array_remove(array_agg(kanji.json), null)), '[]'::json)
                 ) json
      FROM entr entry
               JOIN mv_forms forms
                    ON forms.entr = entry.id
               JOIN mv_senses senses
                    ON senses.entr = entry.id
               LEFT JOIN mv_example example
                         ON example.id = (SELECT e.id
                                          FROM mv_example e
                                          WHERE e.tsv @@ plainto_tsquery('japanese', forms.json -> 0 -> 'kanji' ->> 'literal')
                                             OR e.tsv @@ plainto_tsquery('japanese', forms.json -> 0 -> 'reading' ->> 'literal')
                                          LIMIT 1)
               LEFT JOIN mv_kanji kanji
                         ON kanji.json ->> 'literal' = ANY
                            (regexp_split_to_array(forms.json -> 0 -> 'kanji' ->> 'literal', '\.*'))
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
      GROUP BY entry.id, forms.json, senses.json, example.json) json
        """)
                .bind("pageSize", pageSize)
                .bind("page", page)
                .bind("query", query)
                .bind("japanese", converter.convertRomajiToHiragana(query))
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
SELECT coalesce(json_agg(json), '[]'::json) json FROM mv_kanji
WHERE (json ->> 'id')::integer = ANY (SELECT character
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
                                          WHERE literal = :query)
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

    fun createUser(username: String, email: String, password: String): Mono<String> {
        return client.execute("""
INSERT INTO users (snowflake, email, hash, username)
SELECT :snowflake, :email, crypt(:password, gen_salt('md5')), :username
WHERE NOT EXISTS(
        SELECT * FROM users WHERE email = :email
    )
RETURNING json_build_object(
        'snowflake', snowflake
    ) json
        """)
                .bind("snowflake", Snowflake.next().toString())
                .bind("email", email)
                .bind("password", password)
                .bind("username", username)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun createToken(email: String, password: String): Mono<String> {
        return client.execute("""
INSERT INTO userTokens (snowflake, token)
SELECT users.snowflake, gen_random_uuid() token
FROM users
WHERE EXISTS(
              SELECT snowflake FROM users WHERE email = :email AND hash = crypt(:password, hash)
          )
RETURNING token
        """)
                .bind("email", email)
                .bind("password", password)
                .fetch()
                .first()
                .map { it["token"] as String }
    }

    fun deleteToken(token: String): Mono<Void> {
        return client.execute("""
DELETE
FROM userTokens
WHERE token = :token
        """)
                .bind("token", token)
                .fetch()
                .first()
                .then()
    }

    fun getSelf(token: String): Mono<String> {
        return client.execute("""
SELECT json_build_object(
               'snowflake', snowflake,
               'email', email,
               'username', username
           ) json
FROM users
WHERE EXISTS(
              SELECT snowflake FROM userTokens WHERE token = :token
          )
        """)
                .bind("token", token)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }
}