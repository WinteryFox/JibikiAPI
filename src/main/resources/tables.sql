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

DROP MATERIALIZED VIEW IF EXISTS mv_kanji;
CREATE MATERIALIZED VIEW mv_kanji AS
SELECT json_build_object(
               'id', character.id,
               'literal', character.literal,
               'definitions', meaning.json,
               'readings', json_build_object(
                       'onyomi', coalesce(readings.onyomi, '[]'::json),
                       'kunyomi', coalesce(readings.kunyomi, '[]'::json)
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
           )::jsonb json
FROM character
         LEFT JOIN (SELECT character,
                           json_agg(meaning) json
                    FROM meaning
                    WHERE language = 'en'
                    GROUP BY character) meaning
                   ON meaning.character = character.id
         LEFT JOIN miscellaneous misc on character.id = misc.character
         LEFT JOIN (SELECT character,
                           json_agg(reading) FILTER (WHERE type = 'ja_on') onyomi,
                           json_agg(reading) FILTER (WHERE type = 'ja_kun') kunyomi
                    FROM reading
                    GROUP BY character) readings
                   ON readings.character = character.id;
VACUUM ANALYZE mv_kanji;
CREATE INDEX mv_kanji_literal_index ON mv_kanji ((json ->> 'literal'));

DROP MATERIALIZED VIEW IF EXISTS mv_example;
CREATE MATERIALIZED VIEW mv_example AS
SELECT json_build_object(
               'id', sentences.id,
               'language', sentences.lang,
               'sentence', sentences.sentence,
               'translations', json_agg(
                       json_build_object(
                               'id', translation.id,
                               'language', translation.lang,
                               'sentence', translation.sentence
                           )
                   )
           )::jsonb json,
       sentences.id,
       sentences.tsv
FROM sentences
         JOIN links
              ON links.source = sentences.id
         JOIN sentences translation
              ON translation.id = links.translation
                  AND translation.lang = 'eng'
WHERE sentences.lang = 'jpn'
GROUP BY sentences.id;
CREATE INDEX mv_example_id_index ON mv_example (id);
CREATE INDEX mv_example_tsv_index ON mv_example USING gin (tsv);
VACUUM ANALYZE mv_example;

DROP MATERIALIZED VIEW IF EXISTS mv_senses;
CREATE MATERIALIZED VIEW mv_senses AS
SELECT entr,
       json_agg(
               json_build_object(
                       'definitions', gloss.txt,
                       'part_of_speech', coalesce(pos, '[]'::jsonb),
                       'field_of_use', coalesce(fld, '[]'::jsonb),
                       'miscellaneous', coalesce(descr, '[]'::jsonb)
                   )
           )::jsonb json
FROM (SELECT gloss.entr                entr,
             json_agg(gloss.txt)       txt,
             min(pos.pos::text)::jsonb pos,
             min(fld.fld::text)::jsonb fld,
             misc.descr
      FROM gloss
               LEFT JOIN (SELECT pos.entr,
                                 pos.sens,
                                 json_agg(
                                         json_build_object('short', kwpos.kw, 'long', kwpos.descr))::jsonb pos
                          FROM pos
                                   JOIN kwpos ON pos.kw = kwpos.id
                          GROUP BY pos.entr, pos.sens) pos
                         ON pos.entr = gloss.entr AND pos.sens = gloss.sens
               LEFT JOIN (SELECT fld.entr,
                                 fld.sens,
                                 json_agg(
                                         json_build_object('short', kwfld.kw, 'long', kwfld.descr))::jsonb fld
                          FROM fld
                                   JOIN kwfld ON fld.kw = kwfld.id
                          GROUP BY fld.entr, fld.sens) fld
                         ON fld.entr = gloss.entr AND fld.sens = gloss.sens
               LEFT JOIN (SELECT misc.entr, misc.sens, json_agg(kwmisc.descr)::jsonb descr
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
       json_agg(json_build_object(
               'kanji', json_build_object('literal', kanji.txt, 'info', kinf.descr),
               'reading', json_build_object('literal', reading.txt, 'info', rinf.descr)
           ))::jsonb json
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