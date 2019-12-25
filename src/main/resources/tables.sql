CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS trgm_gloss_index ON gloss USING GIN (txt gin_trgm_ops);

CREATE INDEX IF NOT EXISTS trgm_kanji_index ON kanj USING GIN (txt gin_trgm_ops);

CREATE INDEX IF NOT EXISTS trgm_reading_index ON rdng USING GIN (txt gin_trgm_ops);

CREATE INDEX trgm_gloss_index ON gloss (regexp_replace(txt, '\s\(.*\)', ''));

CREATE INDEX IF NOT EXISTS gloss_index ON gloss (entr, sens, txt);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_senses AS
SELECT entr,
       json_agg(
               json_build_object(
                       'definitions', gloss.txt,
                       'part_of_speech', coalesce(pos, '[]'),
                       'field_of_use', coalesce(fld, '[]')
                   )
           )::jsonb json
FROM (SELECT gloss.entr,
             json_agg(gloss.txt)       txt,
             min(pos.pos::text)::jsonb pos,
             min(fld.fld::text)::jsonb fld
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
      GROUP BY gloss.entr, gloss.sens) gloss
GROUP BY gloss.entr;
CREATE UNIQUE INDEX ON mv_senses (entr);
VACUUM ANALYZE mv_senses;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_forms AS
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
    snowflake TEXT                        NOT NULL PRIMARY KEY,
    creation  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
    email     TEXT                        NOT NULL,
    hash      TEXT                        NOT NULL,
    username  TEXT                        NOT NULL
);