set -x

apt-get update
apt-get install --no-install-recommends -y tar build-essential wget postgresql-server-dev-12 libmecab-dev \
  postgresql-12 curl python3-venv python3-pip default-jre git maven expect mecab-jumandic mecab-jumandic-utf8 \
  mecab-utils python3-setuptools python3-dev python3-wheel ||
  return 1
rm -rf /var/lib/apt/lists/*

sed -i 's/peer/trust/g' /etc/postgresql/12/main/pg_hba.conf
sed -i 's/md5/trust/g' /etc/postgresql/12/main/pg_hba.conf

cd /var/jibiki_deps || return 1
git clone https://gitlab.com/yamagoya/jmdictdb.git
git clone https://github.com/oknj/textsearch_ja.git
git clone https://github.com/WinteryFox/KanjidicParser.git

cd /var/jibiki_deps/jmdictdb || return 1
/etc/init.d/postgresql restart
make init
python3 -m venv jmdictvenv
/bin/bash -c "source jmdictvenv/bin/activate"
python3 -m pip install psycopg2
make loadall
make activate DBACT=jibiki

cd /var/jibiki_deps/textsearch_ja || return 1
make
make install

cd /var/jibiki_deps/KanjidicParser || return 1
mvn package
cd target || return 1
java -jar ./kanjidicparser-1.0-SNAPSHOT-jar-with-dependencies.jar localhost jibiki postgres postgres

echo "host all  all    0.0.0.0/0  trust" >>/etc/postgresql/12/main/pg_hba.conf
echo "listen_addresses='*'" >>/etc/postgresql/12/main/postgresql.conf

/etc/init.d/postgresql start

cd /var/jibiki_deps || return 1
wget https://raw.githubusercontent.com/aehlke/jlpt-classifier/master/jlpt-n1.csv
wget https://raw.githubusercontent.com/aehlke/jlpt-classifier/master/jlpt-n2.csv
wget https://raw.githubusercontent.com/aehlke/jlpt-classifier/master/jlpt-n3.csv
wget https://raw.githubusercontent.com/aehlke/jlpt-classifier/master/jlpt-n4.csv
wget https://raw.githubusercontent.com/aehlke/jlpt-classifier/master/jlpt-n5.csv

psql -U postgres -d jibiki \
  -c "ALTER TABLE entr ADD COLUMN jlpt INTEGER;" \
  -c "CREATE TEMPORARY TABLE n1(seq INTEGER);" \
  -c "\copy n1 FROM '/var/jibiki_deps/jlpt-n1.csv';" \
  -c "CREATE TEMPORARY TABLE n2(seq INTEGER);" \
  -c "\copy n2 FROM '/var/jibiki_deps/jlpt-n2.csv';" \
  -c "CREATE TEMPORARY TABLE n3(seq INTEGER);" \
  -c "\copy n3 FROM '/var/jibiki_deps/jlpt-n3.csv';" \
  -c "CREATE TEMPORARY TABLE n4(seq INTEGER);" \
  -c "\copy n4 FROM '/var/jibiki_deps/jlpt-n4.csv';" \
  -c "CREATE TEMPORARY TABLE n5(seq INTEGER);" \
  -c "\copy n5 FROM '/var/jibiki_deps/jlpt-n5.csv';" \
  -c "UPDATE entr SET jlpt = 1 WHERE EXISTS (SELECT * FROM n1 WHERE n1.seq = entr.seq);" \
  -c "UPDATE entr SET jlpt = 2 WHERE EXISTS (SELECT * FROM n2 WHERE n2.seq = entr.seq);" \
  -c "UPDATE entr SET jlpt = 3 WHERE EXISTS (SELECT * FROM n3 WHERE n3.seq = entr.seq);" \
  -c "UPDATE entr SET jlpt = 4 WHERE EXISTS (SELECT * FROM n4 WHERE n4.seq = entr.seq);" \
  -c "UPDATE entr SET jlpt = 5 WHERE EXISTS (SELECT * FROM n5 WHERE n5.seq = entr.seq);" || return 1

wget http://downloads.tatoeba.org/exports/sentences.tar.bz2 && tar xvfj sentences.tar.bz2
wget http://downloads.tatoeba.org/exports/links.tar.bz2 && tar xvfj links.tar.bz2
wget http://downloads.tatoeba.org/exports/tags.tar.bz2 && tar xvfj tags.tar.bz2
wget http://downloads.tatoeba.org/exports/sentences_with_audio.tar.bz2 && tar xvfj sentences_with_audio.tar.bz2

psql -U postgres -d jibiki \
  -c "CREATE EXTENSION textsearch_ja;" \
  -c "CREATE TEMPORARY TABLE t (id INTEGER, language TEXT, sentence TEXT NOT NULL);" \
  -c "\copy t FROM '/var/jibiki_deps/sentences.csv';" \
  -c "CREATE TABLE sentences AS (SELECT id, language, sentence, CASE WHEN language = 'eng' THEN to_tsvector('english', sentence) ELSE to_tsvector('japanese', sentence) END tsv FROM t WHERE language IN ('eng', 'jpn'));" \
  -c "ALTER TABLE sentences ADD PRIMARY KEY (id);" \
  -c "ALTER TABLE sentences ALTER COLUMN language SET NOT NULL;" \
  -c "CREATE TEMPORARY TABLE l(source INTEGER, translation INTEGER);" \
  -c "\copy l FROM '/var/jibiki_deps/links.csv';" \
  -c "CREATE TABLE links AS (SELECT source, translation FROM l WHERE EXISTS(SELECT * FROM sentences WHERE id = source) AND EXISTS(SELECT * FROM sentences WHERE id = translation));" \
  -c "ALTER TABLE links ADD PRIMARY KEY (source, translation);" \
  -c "ALTER TABLE links ADD FOREIGN KEY (source) REFERENCES sentences(id);" \
  -c "ALTER TABLE links ADD FOREIGN KEY (translation) REFERENCES sentences(id);" \
  -c "CREATE TEMPORARY TABLE ta (sentence INTEGER, tag TEXT);" \
  -c "\copy ta FROM '/var/jibiki_deps/tags.csv';" \
  -c "CREATE TABLE tags AS (SELECT sentence, tag FROM ta WHERE EXISTS(SELECT * FROM sentences WHERE ta.sentence = id));" \
  -c "ALTER TABLE tags ADD PRIMARY KEY(sentence, tag);" \
  -c "ALTER TABLE tags ADD FOREIGN KEY(sentence) REFERENCES sentences(id);" \
  -c "CREATE TEMPORARY TABLE a(sentence INTEGER, username TEXT, license TEXT, attribution TEXT);" \
  -c "\copy a FROM '/var/jibiki_deps/sentences_with_audio.csv';" \
  -c "ALTER TABLE sentences ADD COLUMN has_audio BOOLEAN DEFAULT false;" \
  -c "UPDATE sentences SET has_audio = true WHERE EXISTS (SELECT * FROM a WHERE a.sentence = id);" \
  -c "ALTER TABLE sentences ALTER COLUMN has_audio SET NOT NULL;" ||
  return 1
psql -U postgres -f /var/jibiki_deps/scripts/tables_readonly.sql jibiki

apt-get remove -y postgresql-server-dev-12 libmecab-dev curl python3-venv python3-pip default-jre git maven expect python3-setuptools python3-dev python3-wheel
rm -rf /var/jibiki_deps
/etc/init.d/postgresql stop
