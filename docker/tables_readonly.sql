ALTER SYSTEM SET work_mem = '1GB';
CREATE EXTENSION pg_trgm;

---------------
---- KANJI ----
---------------

CREATE MATERIALIZED VIEW mv_kanji AS
SELECT character.literal,
       jsonb_build_object(
               'literal', character.literal,
               'definitions',
               coalesce(meaning.json, '[]'::jsonb),
               'readings', jsonb_build_object(
                       'onyomi', coalesce(reading.onyomi, '[]'::jsonb),
                       'kunyomi', coalesce(reading.kunyomi, '[]'::jsonb)
                   ),
               'miscellaneous', jsonb_build_object(
                       'grade', miscellaneous.grade,
                       'stroke_count', miscellaneous.stroke_count,
                       'frequency', miscellaneous.frequency,
                       'variant_type', miscellaneous.variant_type,
                       'variant', miscellaneous.variant,
                       'jlpt', miscellaneous.jlpt,
                       'radical_name', miscellaneous.radical_name
                   )
           ) json
FROM character
         LEFT JOIN miscellaneous
                   ON miscellaneous.character = character.literal
         JOIN LATERAL (SELECT reading.character,
                              jsonb_agg(reading.reading) FILTER (WHERE type = 'ja_on')  onyomi,
                              jsonb_agg(reading.reading) FILTER (WHERE type = 'ja_kun') kunyomi
                       FROM reading
                       WHERE reading.character = character.literal
                       GROUP BY reading.character) reading
              ON TRUE
         JOIN LATERAL (SELECT character,
                              jsonb_agg(meaning) FILTER (WHERE language = 'en') json
                       FROM meaning
                       WHERE meaning.character = character.literal
                       GROUP BY meaning.character) meaning
              ON TRUE;

CREATE INDEX meaning_meaning_lower_index ON meaning (lower(meaning));
CREATE INDEX reading_reading_index ON reading (reading);
CREATE INDEX reading_reading_replace_index ON reading (REPLACE(reading, '.', ''));
CREATE INDEX mv_kanji_literal_index ON mv_kanji (literal);

-------------------
---- SENTENCES ----
-------------------

CREATE VIEW v_sentences AS
SELECT sentences.id,
       sentences.language,
       jsonb_build_object(
               'id', sentences.id,
               'language', sentences.language,
               'sentence', sentences.sentence,
               'audio_uri', CASE
                                WHEN sentences.has_audio IS NOT NULL THEN
                                            'https://audio.tatoeba.org/sentences/' || sentences.language || '/' ||
                                            sentences.id || '.mp3' END,
               'tags', coalesce(jsonb_agg(tags.tag) FILTER (WHERE tag != 'null'), '[]'::jsonb)
           ) json
FROM sentences
         LEFT JOIN tags
                   ON sentences.id = tags.sentence
GROUP BY sentences.id;

CREATE MATERIALIZED VIEW mv_translated_sentences AS
SELECT source.id,
       source.language,
       jsonb_insert(
               source.json,
               '{translations}'::text[],
               jsonb_agg(translations.json)
           ) json
FROM links
         JOIN v_sentences source
              ON links.source = source.id
         JOIN v_sentences translations
              ON links.translation = translations.id
                  AND translations.language != source.language
GROUP BY source.id, source.language, source.json;

CREATE INDEX sentences_language ON sentences (language);
CREATE INDEX sentences_tsv_index ON sentences USING gin (tsv);
CREATE INDEX tags_sentence_index ON tags (sentence);

CREATE FUNCTION get_sentences(query TEXT, minLength INT, maxLength INT, page INT, pageSize INT)
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

---------------
---- WORDS ----
---------------

ALTER TABLE entr
    ADD COLUMN IF NOT EXISTS score SMALLINT NOT NULL DEFAULT 0;

CREATE FUNCTION score_word() RETURNS TRIGGER AS
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

CREATE TRIGGER word_trigger
    BEFORE UPDATE OR INSERT
    ON entr
    FOR EACH ROW
EXECUTE PROCEDURE score_word();

UPDATE entr
SET score = 0
WHERE TRUE;

DROP TRIGGER word_trigger ON entr;
DROP FUNCTION score_word();

CREATE INDEX entr_id_cast_index ON entr (cast(id AS text));
CREATE INDEX rdng_txt_index ON rdng (txt);
CREATE INDEX gloss_replace_index ON gloss (regexp_replace(lower(txt), 'to\s|\s\(.*\)', ''));
CREATE INDEX gloss_lower_index ON gloss (lower(txt));

CREATE FUNCTION search_words(query TEXT, japanese TEXT, page INTEGER, pageSize INTEGER)
    RETURNS SETOF INTEGER
AS
$$
SELECT id
FROM entr
         JOIN (SELECT entr
               FROM gloss
               WHERE txt = query
               UNION
               SELECT entr
               FROM kanj
               WHERE txt = query
               UNION
               SELECT entr
               FROM rdng
               WHERE txt IN (hiragana(japanese),
                             katakana(japanese))
               UNION
               SELECT entr.id entr
               FROM entr
               WHERE entr.id::text = ANY (regexp_split_to_array(query, ','))
               LIMIT pageSize
               OFFSET
               page * pageSize) entries
              ON entr.id = entries.entr
ORDER BY score DESC;
$$ LANGUAGE SQL STABLE;

CREATE VIEW v_senses AS
SELECT gloss.entr   id,
       gloss.sens   ordinal,
       gloss.txt    definition,
       kwpos.kw     pos,
       kwpos.descr  pos_info,
       kwfld.kw     fld,
       kwfld.descr  fld_info,
       kwmisc.descr misc
FROM gloss
         LEFT JOIN pos
                   ON pos.entr = gloss.entr
                       AND pos.sens = gloss.sens
         LEFT JOIN kwpos
                   ON pos.kw = kwpos.id
         LEFT JOIN fld
                   ON fld.entr = gloss.entr
                       AND fld.sens = gloss.sens
         LEFT JOIN kwfld
                   ON fld.kw = kwfld.id
         LEFT JOIN misc
                   ON misc.entr = gloss.entr
                       AND misc.sens = gloss.sens
         LEFT JOIN kwmisc
                   ON misc.kw = kwmisc.id;

CREATE VIEW v_forms AS
SELECT rdng.entr    id,
       rdng.rdng    ordinal,
       rdng.txt     reading,
       kwrinf.descr reading_info,
       kanj.txt     kanji,
       kwkinf.descr kanji_info
FROM rdng
         LEFT JOIN rinf
                   ON rdng.entr = rinf.entr
                       AND rdng.rdng = rinf.rdng
         LEFT JOIN kwrinf
                   ON rinf.kw = kwrinf.id
         LEFT JOIN kanj
                   ON rdng.entr = kanj.entr
                       AND rdng.rdng = kanj.kanj
         LEFT JOIN kinf
                   ON kanj.entr = kinf.entr
                       AND kanj.kanj = kinf.kanj
         LEFT JOIN kwkinf
                   ON kinf.kw = kwkinf.id;

CREATE FUNCTION get_words(query TEXT, japanese TEXT, page INTEGER, pageSize INTEGER)
    RETURNS SETOF JSONB
AS
$$
WITH entries AS (
    SELECT search_words(query, japanese, page, pageSize) entries
),
     forms AS (
         SELECT form.id,
                form.ordinal,
                jsonb_build_object(
                        'ordinal', form.ordinal,
                        'kanji',
                        jsonb_build_object(
                                'literal', form.kanji,
                                'info', form.kanji_info
                            ),
                        'reading',
                        jsonb_build_object(
                                'literal', form.reading,
                                'info', form.reading_info
                            )
                    ) json
         FROM v_forms form
                  JOIN entries
                       ON form.id = entries
     ),
     senses AS (
         SELECT sens.id,
                sens.ordinal,
                jsonb_build_object(
                        'ordinal', sens.ordinal,
                        'definitions', jsonb_agg(DISTINCT sens.definition),
                        'field_of_use',
                        coalesce(
                                        jsonb_agg(
                                        DISTINCT
                                        jsonb_build_object(
                                                'short', sens.fld,
                                                'long', sens.fld_info
                                            )
                                    ) FILTER (WHERE sens.fld IS NOT NULL), '[]'::jsonb
                            ),
                        'part_of_speech',
                        coalesce(
                                        jsonb_agg(
                                        DISTINCT
                                        jsonb_build_object(
                                                'short', sens.pos,
                                                'long', sens.pos_info
                                            )
                                    ) FILTER (WHERE sens.pos IS NOT NULL), '[]'::jsonb
                            )
                    ) json
         FROM v_senses sens
                  JOIN entries
                       ON sens.id = entries
         GROUP BY sens.id, sens.ordinal
     )
SELECT jsonb_build_object(
               'id', entry.id,
               'seq', entry.seq,
               'jlpt', entry.jlpt,
               'forms', jsonb_agg(DISTINCT forms.json),
               'senses', jsonb_agg(senses.json)
           ) json
FROM entr entry
         JOIN search_words(query, japanese, page, pageSize)
              ON entry.id = search_words
         JOIN forms
              ON forms.id = entry.id
         JOIN senses
              ON senses.id = entry.id
GROUP BY entry.id;
$$ LANGUAGE SQL STABLE;