set -x

sed -i'' 's/archive.ubuntu.com/us.archive.ubuntu.com/' /etc/apt/sources.list
apt-get update
apt-get install --no-install-recommends -y build-essential wget postgresql-server-dev-12 libmecab-dev postgresql-12 curl python3-venv python3-pip default-jre git maven expect mecab-jumandic mecab-jumandic-utf8 mecab-utils python3-setuptools python3-dev
rm -rf /var/lib/apt/lists/*

sed -i 's/peer/trust/g' /etc/postgresql/12/main/pg_hba.conf
sed -i 's/md5/trust/g' /etc/postgresql/12/main/pg_hba.conf

cd /var/jibiki_deps
git clone https://gitlab.com/yamagoya/jmdictdb/
git clone https://github.com/oknj/textsearch_ja.git
git clone https://github.com/WinteryFox/JibikiJLPTClassifier.git
git clone https://github.com/WinteryFox/KanjidicParser.git
git clone https://github.com/WinteryFox/TatoebaPostgreSQL.git

cd /var/jibiki_deps/jmdictdb
/etc/init.d/postgresql restart
make init
python3 -m venv jmdictvenv
/bin/bash -c "source jmdictvenv/bin/activate"
python3 -m pip install -Iv psycopg2==2.7.3.2
make loadall
make activate DBACT=jibiki

cd /var/jibiki_deps/textsearch_ja
make
make install

cd /var/jibiki_deps/TatoebaPostgreSQL
mvn package
cd /var/jibiki_deps/TatoebaPostgreSQL/target
cp /var/jibiki_deps/scripts/tatoeba.exp .
chmod +x ./tatoeba.exp
./tatoeba.exp

cd /var/jibiki_deps/KanjidicParser
curl -L https://github.com/WinteryFox/KanjidicParser/releases/download/1.0.0/kanjidicparser.jar --output kanjidicparser.jar
java -jar ./kanjidicparser.jar localhost jibiki postgres postgres

cd /var/jibiki_deps/JibikiJLPTClassifier
mvn package
cd /var/jibiki_deps/JibikiJLPTClassifier/target
cp /var/jibiki_deps/scripts/jlptclassifier.exp .
chmod +x ./jlptclassifier.exp
./jlptclassifier.exp

echo "host all  all    0.0.0.0/0  trust" >> /etc/postgresql/12/main/pg_hba.conf
echo "listen_addresses='*'" >> /etc/postgresql/12/main/postgresql.conf

psql -U postgres -f /var/jibiki_deps/scripts/tables_readonly.sql jibiki

apt-get remove -y postgresql-server-dev-12 libmecab-dev curl python3-venv python3-pip default-jre git maven expect python3-setuptools python3-dev
rm -rf /var/jibiki_dep
/etc/init.d/postgresql stop
