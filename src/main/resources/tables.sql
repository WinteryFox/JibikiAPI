CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP INDEX IF EXISTS trgm_gloss_index;
CREATE INDEX trgm_gloss_index ON gloss USING GIN (txt gin_trgm_ops);

DROP INDEX IF EXISTS trgm_kanji_index;
CREATE INDEX trgm_kanji_index ON kanj USING GIN (txt gin_trgm_ops);

DROP INDEX IF EXISTS trgm_reading_index;
CREATE INDEX trgm_reading_index ON rdng USING GIN (txt gin_trgm_ops);

DROP INDEX IF EXISTS trgm_gloss_index;
CREATE INDEX trgm_gloss_index ON gloss (regexp_replace(lower(txt), '\s\(.*\)', ''));

DROP INDEX IF EXISTS gloss_index;
CREATE INDEX gloss_index ON gloss (entr, sens, txt);

DROP INDEX IF EXISTS sentences_id_cast_index;
CREATE INDEX sentences_id_cast_index ON sentences (CAST(id AS TEXT));

DROP INDEX IF EXISTS reading_reading_stripped_index;
CREATE INDEX reading_reading_stripped_index ON reading (REPLACE(reading, '.', ''));

DROP INDEX IF EXISTS reading_entr_cast_index;
CREATE INDEX reading_entr_cast_index ON rdng (CAST(entr AS TEXT));

DROP MATERIALIZED VIEW IF EXISTS mv_kanji;
CREATE MATERIALIZED VIEW mv_kanji AS
SELECT jsonb_build_object(
               'id', character.id,
               'literal', character.literal,
               'definitions', meaning.json,
               'readings', jsonb_build_object(
                       'onyomi', coalesce(readings.onyomi, '[]'::jsonb),
                       'kunyomi', coalesce(readings.kunyomi, '[]'::jsonb)
                   ),
               'miscellaneous', jsonb_build_object(
                       'grade', misc.grade,
                       'stroke_count', misc.stroke_count,
                       'frequency', misc.frequency,
                       'variant_type', misc.variant_type,
                       'variant', misc.variant,
                       'jlpt', misc.jlpt,
                       'radical_name', misc.radical_name
                   )
           ) json
FROM character
         LEFT JOIN (SELECT character,
                           jsonb_agg(meaning) json
                    FROM meaning
                    WHERE language = 'en'
                    GROUP BY character) meaning
                   ON meaning.character = character.id
         LEFT JOIN miscellaneous misc on character.id = misc.character
         LEFT JOIN (SELECT character,
                           jsonb_agg(reading) FILTER (WHERE type = 'ja_on')  onyomi,
                           jsonb_agg(reading) FILTER (WHERE type = 'ja_kun') kunyomi
                    FROM reading
                    GROUP BY character) readings
                   ON readings.character = character.id;
VACUUM ANALYZE mv_kanji;
CREATE INDEX mv_kanji_literal_index ON mv_kanji ((json ->> 'literal'));

DROP MATERIALIZED VIEW IF EXISTS mv_sentences CASCADE;
CREATE MATERIALIZED VIEW mv_sentences AS
SELECT jsonb_build_object(
               'id', sentences.id,
               'language', sentences.lang,
               'sentence', sentences.sentence,
               'audio_uri', CASE
                                WHEN audio.sentence IS NOT NULL THEN
                                                    'https://audio.tatoeba.org/sentences/' || sentences.lang || '/' ||
                                                    sentences.id || '.mp3' END
           ) json
FROM sentences
         LEFT JOIN audio on sentences.id = audio.sentence
WHERE sentences.lang IN ('eng', 'jpn');
VACUUM ANALYZE mv_sentences;

DROP MATERIALIZED VIEW IF EXISTS mv_translated_sentences;
CREATE MATERIALIZED VIEW mv_translated_sentences AS
SELECT jsonb_insert(
               source.json,
               '{translations}'::text[],
               jsonb_agg(translations.json)
           ) json
FROM links
         JOIN mv_sentences source ON links.source = (source.json ->> 'id')::integer
         JOIN mv_sentences translations ON links.translation = (translations.json ->> 'id')::integer AND
                                           translations.json ->> 'language' != source.json ->> 'language'
GROUP BY source.json;
VACUUM ANALYZE mv_translated_sentences;

DROP INDEX IF EXISTS mv_translated_sentences_id_index;
CREATE INDEX mv_translated_sentences_id_index ON mv_translated_sentences (((mv_translated_sentences.json ->> 'id')::integer));

DROP INDEX IF EXISTS mv_translated_sentences_language_index;
CREATE INDEX mv_translated_sentences_language_index ON mv_translated_sentences ((mv_translated_sentences.json ->> 'language'));

DROP INDEX IF EXISTS mv_translated_sentences_id_language_index;
CREATE INDEX mv_translated_sentences_id_language_index ON mv_translated_sentences (((mv_translated_sentences.json ->> 'id')::integer),
                                                                                   (mv_translated_sentences.json ->> 'language'));

DROP FUNCTION IF EXISTS get_sentences;
CREATE OR REPLACE FUNCTION get_sentences(query TEXT, minLength INT, maxLength INT, page INT, pageSize INT)
    RETURNS TABLE
            (
                entry INTEGER
            )
AS
$$
WITH entries AS (
    SELECT entries.id entry, tsv
    FROM sentences entries
    WHERE entries.tsv @@ plainto_tsquery('japanese', query)
      AND (entries.lang IN ('eng', 'jpn'))
      AND length(entries.sentence) BETWEEN minLength AND maxLength
    UNION ALL
    SELECT entries.id entry, tsv
    FROM sentences entries
    WHERE entries.id::text = ANY (regexp_split_to_array(query, ','))
    LIMIT pageSize
    OFFSET
    page * pageSize
)
SELECT entry
FROM entries
ORDER BY ts_rank(tsv, plainto_tsquery('japanese', query)) DESC;
$$ LANGUAGE SQL STABLE;

DROP MATERIALIZED VIEW IF EXISTS mv_senses CASCADE;
CREATE MATERIALIZED VIEW mv_senses AS
SELECT entr,
       jsonb_agg(
               jsonb_build_object(
                       'definitions', gloss.txt,
                       'part_of_speech', coalesce(pos, '[]'::jsonb),
                       'field_of_use', coalesce(fld, '[]'::jsonb),
                       'miscellaneous', coalesce(descr, '[]'::jsonb)
                   )
           ) json
FROM (SELECT gloss.entr                entr,
             jsonb_agg(gloss.txt)       txt,
             min(pos.pos::text)::jsonb pos,
             min(fld.fld::text)::jsonb fld,
             misc.descr
      FROM gloss
               LEFT JOIN (SELECT pos.entr,
                                 pos.sens,
                                 jsonb_agg(
                                         jsonb_build_object('short', kwpos.kw, 'long', kwpos.descr)) pos
                          FROM pos
                                   JOIN kwpos ON pos.kw = kwpos.id
                          GROUP BY pos.entr, pos.sens) pos
                         ON pos.entr = gloss.entr AND pos.sens = gloss.sens
               LEFT JOIN (SELECT fld.entr,
                                 fld.sens,
                                 jsonb_agg(
                                         jsonb_build_object('short', kwfld.kw, 'long', kwfld.descr)) fld
                          FROM fld
                                   JOIN kwfld ON fld.kw = kwfld.id
                          GROUP BY fld.entr, fld.sens) fld
                         ON fld.entr = gloss.entr AND fld.sens = gloss.sens
               LEFT JOIN (SELECT misc.entr, misc.sens, jsonb_agg(kwmisc.descr) descr
                          FROM misc
                                   LEFT JOIN kwmisc ON misc.kw = kwmisc.id
                          GROUP BY misc.entr, misc.sens) misc
                         ON misc.entr = gloss.entr AND misc.sens = gloss.sens
      GROUP BY gloss.entr, gloss.sens, misc.descr) gloss
GROUP BY entr;
CREATE INDEX mv_senses_entr_index ON mv_senses (entr);
VACUUM ANALYZE mv_senses;

DROP MATERIALIZED VIEW IF EXISTS mv_forms;
CREATE MATERIALIZED VIEW mv_forms AS
SELECT reading.entr,
       jsonb_agg(jsonb_build_object(
               'kanji', jsonb_build_object('literal', kanji.txt, 'info', kinf.descr),
               'reading', jsonb_build_object('literal', reading.txt, 'info', rinf.descr)
           )) json
FROM rdng reading
         LEFT JOIN (SELECT kwrinf.descr, rinf.entr, rinf.rdng
                    FROM rinf
                             LEFT JOIN kwrinf ON kwrinf.id = rinf.kw) rinf
                   ON rinf.entr = reading.entr AND rinf.rdng = reading.rdng
         LEFT JOIN kanj kanji
                   ON kanji.entr = reading.entr AND kanji.kanj = reading.rdng
         LEFT JOIN (SELECT kwkinf.descr, kinf.entr, kinf.kanj
                    FROM kinf
                             LEFT JOIN kwkinf ON kwkinf.id = kinf.kw) kinf
                   ON kinf.entr = reading.entr AND kinf.kanj = kanji.kanj
GROUP BY reading.entr;
CREATE UNIQUE INDEX ON mv_forms (entr);
VACUUM ANALYZE mv_forms;

ALTER TABLE entr
    ADD COLUMN IF NOT EXISTS score SMALLINT NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION score_word() RETURNS TRIGGER AS
$$
DECLARE
    f INTEGER;
BEGIN
    new.score := (SELECT count(*)
                  FROM gloss
                  WHERE entr = new.id) +
                 (SELECT count(*)
                  FROM pos
                  WHERE entr = new.id) +
                 (SELECT count(*)
                  FROM fld
                  WHERE entr = new.id);

    FOR f IN SELECT kw
             FROM freq
             WHERE freq.entr = new.id
        LOOP
            IF f IN (1, 7, 4, 2) THEN
                new.score := new.score + 5;
            END IF;
            IF f IN (3, 5, 6) THEN
                new.score := new.score + 1;
            END IF;
        END LOOP;

    RETURN new;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS word_trigger ON entr;
CREATE TRIGGER word_trigger
    BEFORE UPDATE OR INSERT
    ON entr
    FOR EACH ROW
EXECUTE PROCEDURE score_word();

UPDATE entr
SET score = 0
WHERE TRUE;

CREATE OR REPLACE FUNCTION get_words(query TEXT, japanese TEXT, page INTEGER, pageSize INTEGER)
    RETURNS TABLE
            (
                entry INTEGER
            )
AS
$$
BEGIN
    RETURN QUERY SELECT id
                 FROM entr
                 WHERE id IN (SELECT entr
                              FROM gloss
                              WHERE regexp_replace(lower(txt), '\s\(.*\)', '') = lower(query)
                              UNION
                              SELECT entr
                              FROM kanj
                              WHERE txt % query
                              UNION
                              SELECT entr
                              FROM rdng
                              WHERE txt IN (hiragana(japanese),
                                            katakana(japanese))
                                 OR entr::text = ANY (regexp_split_to_array(query, ','))
                              LIMIT pageSize
                              OFFSET
                              page * pageSize)
                 ORDER BY score DESC;
END;
$$ LANGUAGE plpgsql;

DROP MATERIALIZED VIEW IF EXISTS mv_words;
CREATE MATERIALIZED VIEW mv_words AS
SELECT jsonb_build_object(
               'id', entry.id,
               'jlpt', entry.jlpt,
               'forms', forms.json,
               'senses', senses.json
           ) json
FROM entr entry
         JOIN mv_forms forms
              ON forms.entr = entry.id
         JOIN mv_senses senses
              ON senses.entr = entry.id
WHERE src != 3;

CREATE INDEX IF NOT EXISTS mv_words_id_index ON mv_words (((json ->> 'id')::integer));

CREATE TABLE IF NOT EXISTS users
(
    snowflake TEXT NOT NULL PRIMARY KEY,
    email     TEXT NOT NULL,
    hash      TEXT NOT NULL,
    username  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS userTokens
(
    snowflake TEXT NOT NULL REFERENCES users (snowflake),
    token     TEXT NOT NULL,
    PRIMARY KEY (snowflake, token)
);

CREATE TABLE IF NOT EXISTS bookmarks
(
    snowflake TEXT     NOT NULL REFERENCES users (snowflake),
    type      SMALLINT NOT NULL,
    bookmark  INTEGER  NOT NULL,
    PRIMARY KEY (snowflake, type, bookmark)
);