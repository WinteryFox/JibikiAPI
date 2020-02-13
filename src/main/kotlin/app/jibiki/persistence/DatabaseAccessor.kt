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

    fun getAll(query: String, sentenceCount: Int, page: Int): Mono<String> {
        return client.execute("""
SELECT coalesce(jsonb_agg(json.json), '[]'::jsonb) json
FROM (SELECT jsonb_build_object(
                     'word', mv_words.json,
                     'kanji',
                     coalesce(jsonb_agg(DISTINCT kanji.json) FILTER (WHERE kanji.json IS NOT NULL), '[]'::jsonb),
                     'sentences',
                     coalesce(jsonb_agg(DISTINCT example.json) FILTER (WHERE example.json IS NOT NULL), '[]'::jsonb)
                 ) json
      FROM mv_words
               JOIN get_words(:query, :japanese, :page, :pageSize) words
                    ON (mv_words.json ->> 'id')::integer = words
               LEFT JOIN mv_kanji kanji
                         ON kanji.json ->> 'literal' = ANY
                            (regexp_split_to_array(mv_words.json -> 'forms' -> 0 -> 'kanji' ->> 'literal', '\.*'))
               LEFT JOIN get_sentences(
              CASE
                  WHEN mv_words.json -> 'forms' -> 0 -> 'kanji' ->> 'literal' IS NOT NULL THEN
                      mv_words.json -> 'forms' -> 0 -> 'kanji' ->> 'literal'
                  ELSE
                      mv_words.json -> 'forms' -> 0 -> 'reading' ->> 'literal'
                  END,
              0,
              10000,
              0,
              :sentenceCount
          ) s
                         ON TRUE
               LEFT JOIN mv_translated_sentences example
                         ON (example.json ->> 'id')::integer = s
      GROUP BY mv_words.json) json
        """)
                .bind("pageSize", pageSize)
                .bind("page", page)
                .bind("query", query)
                .bind("japanese", converter.convertRomajiToHiragana(query))
                .bind("sentenceCount", sentenceCount)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun getWords(query: String, page: Int): Mono<String> {
        return client.execute("""
SELECT coalesce(jsonb_agg(json), '[]'::jsonb) json
FROM mv_words
         JOIN get_words(:query, :japanese, :page, :pageSize) words
              ON (json ->> 'id')::integer = words
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
WITH entries AS (
    SELECT character
    FROM meaning
    WHERE lower(meaning) = lower(: query)
    UNION
    SELECT character
    FROM reading
    WHERE reading IN (hiragana(:japanese),
                      katakana(:japanese))
       OR REPLACE(reading, '.', '') IN (hiragana(:japanese),
                                        katakana(:japanese))
    UNION
    SELECT literal
    FROM character
    WHERE literal = ANY (regexp_split_to_array(: query, ''))
       OR literal = ANY (regexp_split_to_array(: query, ','))
)
SELECT coalesce(jsonb_agg(v_kanji.json), '[]'::jsonb)
FROM entries
         JOIN v_kanji
              ON v_kanji.literal = entries.character
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

    fun getSentences(query: String, page: Int, minLength: Int, maxLength: Int, source: String): Mono<String> {
        return client.execute("""
SELECT coalesce(jsonb_agg(DISTINCT json), '[]'::jsonb) json
FROM links
         JOIN get_sentences(:query, :minLength, :maxLength, :page, :pageSize) entries
              ON entries = links.source
         JOIN mv_translated_sentences
              ON (mv_translated_sentences.json ->> 'id')::integer IN
                 (links.source, links.translation)
                  AND mv_translated_sentences.json ->> 'language' = :source
        """)
                .bind("pageSize", pageSize)
                .bind("page", page)
                .bind("query", query)
                .bind("minLength", minLength)
                .bind("maxLength", maxLength)
                .bind("source", source)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun createUser(username: String, email: String, password: String): Mono<String> {
        return client.execute("""
INSERT INTO users (snowflake, email, hash, username)
SELECT :snowflake, :email, crypt(:password, gen_salt('md5')), :username
WHERE NOT exists(
        SELECT * FROM users WHERE email = :email
    )
RETURNING jsonb_build_object(
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
SELECT users.snowflake, encode(gen_random_uuid()::text::bytea, 'base64')
FROM users
WHERE email = :email
  AND hash = crypt(:password, hash)
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
SELECT jsonb_build_object(
               'snowflake', users.snowflake,
               'email', users.email,
               'username', users.username,
               'bookmarks', jsonb_build_object(
                       'words', coalesce(jsonb_agg(b.bookmark) FILTER (WHERE b.type = 0), '[]'::jsonb),
                       'kanji', coalesce(jsonb_agg(b.bookmark) FILTER (WHERE b.type = 1), '[]'::jsonb),
                       'sentences', coalesce(jsonb_agg(b.bookmark) FILTER (WHERE b.type = 2), '[]'::jsonb)
                   )
           ) json
FROM users
         LEFT JOIN bookmarks b on users.snowflake = b.snowflake
WHERE users.snowflake = (SELECT snowflake FROM userTokens WHERE token = :token)
GROUP BY users.snowflake
        """)
                .bind("token", token)
                .fetch()
                .first()
                .map { (it["json"] as Json).asString() }
    }

    fun createBookmark(token: String, type: Int, bookmark: Int): Mono<Int> {
        return client.execute("""
INSERT INTO bookmarks (snowflake, type, bookmark)
SELECT snowflake, :type, :bookmark
FROM users
WHERE snowflake = (SELECT snowflake FROM userTokens WHERE token = :token)
ON CONFLICT DO NOTHING
        """)
                .bind("token", token)
                .bind("type", type)
                .bind("bookmark", bookmark)
                .fetch()
                .rowsUpdated()
    }

    fun deleteBookmark(token: String, type: Int, bookmark: Int): Mono<Int> {
        return client.execute("""
DELETE
FROM bookmarks
WHERE snowflake = (SELECT snowflake FROM userTokens WHERE token = :token)
  AND type = :type
  AND bookmark = :bookmark
        """)
                .bind("token", token)
                .bind("type", type)
                .bind("bookmark", bookmark)
                .fetch()
                .rowsUpdated()
    }
}