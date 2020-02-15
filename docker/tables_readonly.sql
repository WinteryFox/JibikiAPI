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

CREATE VIEW mv_senses AS
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
             jsonb_agg(gloss.txt)      txt,
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

CREATE VIEW mv_forms AS
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

CREATE VIEW v_words AS
SELECT entry.id,
       jsonb_build_object(
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
