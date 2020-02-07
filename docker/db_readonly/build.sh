set -x

apt-get update
apt-get install --no-install-recommends -y tar build-essential wget postgresql-server-dev-12 libmecab-dev postgresql-12 curl python3-venv python3-pip default-jre git maven expect mecab-jumandic mecab-jumandic-utf8 mecab-utils python3-setuptools python3-dev
rm -rf /var/lib/apt/lists/*

sed -i 's/peer/trust/g' /etc/postgresql/12/main/pg_hba.conf
sed -i 's/md5/trust/g' /etc/postgresql/12/main/pg_hba.conf

cd /var/jibiki_deps || return 1
#git clone https://gitlab.com/yamagoya/jmdictdb/
git clone https://github.com/oknj/textsearch_ja.git
#git clone https://github.com/WinteryFox/JibikiJLPTClassifier.git
#git clone https://github.com/WinteryFox/KanjidicParser.git
#
#cd /var/jibiki_deps/jmdictdb
#/etc/init.d/postgresql restart
#make init
#python3 -m venv jmdictvenv
#/bin/bash -c "source jmdictvenv/bin/activate"
#python3 -m pip install -Iv psycopg2==2.7.3.2
#make loadall
#make activate DBACT=jibiki
#
cd /var/jibiki_deps/textsearch_ja || return 1
make
make install
#
#cd /var/jibiki_deps
#wget http://downloads.tatoeba.org/exports/sentences.tar.bz2 && tar xvfj sentences.tar.bz2
#wget http://downloads.tatoeba.org/exports/links.tar.bz2 && tar xvfj links.tar.bz2
#wget http://downloads.tatoeba.org/exports/tags.tar.bz2 && tar xvfj tags.tar.bz2
#wget http://downloads.tatoeba.org/exports/sentences_with_audio.tar.bz2 && tar xvfj sentences_with_audio.tar.bz2
#psql -U postgres -d jibiki -c "CREATE TABLE sentences(id INTEGER PRIMARY KEY, language TEXT NOT NULL, sentence TEXT NOT NULL)"
#psql -U postgres -d jibiki -c "\copy sentences FROM '/var/jibiki_deps/sentences.csv' (FORMAT CSV, DELIMITER E'\t', QUOTE '')"
#
#cd /var/jibiki_deps/KanjidicParser
#curl -L https://github.com/WinteryFox/KanjidicParser/releases/download/1.0.0/kanjidicparser.jar --output kanjidicparser.jar
#java -jar ./kanjidicparser.jar localhost jibiki postgres postgres
#
#cd /var/jibiki_deps/JibikiJLPTClassifier
#mvn package
#cd /var/jibiki_deps/JibikiJLPTClassifier/target
#cp /var/jibiki_deps/scripts/jlptclassifier.exp .
#chmod +x ./jlptclassifier.exp
#./jlptclassifier.exp

echo "host all  all    0.0.0.0/0  trust" >> /etc/postgresql/12/main/pg_hba.conf
echo "listen_addresses='*'" >> /etc/postgresql/12/main/postgresql.conf

cd /var/jibiki_deps || return 1
wget http://downloads.tatoeba.org/exports/sentences.tar.bz2 && tar xvfj sentences.tar.bz2
wget http://downloads.tatoeba.org/exports/links.tar.bz2 && tar xvfj links.tar.bz2
wget http://downloads.tatoeba.org/exports/tags.tar.bz2 && tar xvfj tags.tar.bz2
wget http://downloads.tatoeba.org/exports/sentences_with_audio.tar.bz2 && tar xvfj sentences_with_audio.tar.bz2

/etc/init.d/postgresql start
psql -U postgres -d postgres -c "CREATE DATABASE jibiki;"
psql -U postgres -d jibiki \
-c "CREATE EXTENSION textsearch_ja;" \
-c "CREATE TEMPORARY TABLE t (id INTEGER, language TEXT, sentence TEXT NOT NULL);" \
-c "\copy t FROM '/var/jibiki_deps/sentences.csv';" \
-c "CREATE TABLE sentences AS (SELECT id, language, sentence, CASE WHEN language = 'eng' THEN to_tsvector('english', sentence) ELSE to_tsvector('japanese', sentence) END tsv FROM t WHERE language IN ('eng', 'jpn'));" \
-c "ALTER TABLE sentences ADD PRIMARY KEY (id);" \
-c "ALTER TABLE sentences ALTER COLUMN language SET NOT NULL;" \
-c "CREATE TEMPORARY TABLE l(source INTEGER, translation INTEGER);" \
-c "\copy l FROM '/var/jibiki_deps/links.csv';" \
-c "CREATE TABLE links AS (SELECT source, translation FROM l WHERE EXISTS (SELECT * FROM sentences WHERE id IN (source, translation) LIMIT 1));" \
-c "ALTER TABLE links ADD PRIMARY KEY (source, translation);" \
-c "ALTER TABLE links ADD FOREIGN KEY (source) REFERENCES sentences(id);" \
-c "ALTER TABLE links ADD FOREIGN KEY (translation) REFERENCES sentences(id);" \
-c "" \
|| return 1
#psql -U postgres -f /var/jibiki_deps/scripts/tables_readonly.sql jibiki

apt-get remove -y postgresql-server-dev-12 libmecab-dev curl python3-venv python3-pip default-jre git maven expect python3-setuptools python3-dev
rm -rf /var/jibiki_deps
/etc/init.d/postgresql stop
