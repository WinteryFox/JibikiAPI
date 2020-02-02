FROM ubuntu:18.04 as DbBuilder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y gnupg2 wget
RUN echo "deb http://apt.postgresql.org/pub/repos/apt/ bionic-pgdg main" > /etc/apt/sources.list.d/pgdg.list
RUN wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
RUN sed -i'' 's/archive.ubuntu.com/us.archive.ubuntu.com/' /etc/apt/sources.list
RUN apt-get update && apt-get install -y postgresql-server-dev-11 libmecab-dev postgresql-11 curl python3-venv python3-pip default-jre git maven expect

RUN sed -i 's/peer/trust/g' /etc/postgresql/11/main/pg_hba.conf
RUN sed -i 's/md5/trust/g' /etc/postgresql/11/main/pg_hba.conf

WORKDIR /var/jibiki_deps
RUN git clone https://gitlab.com/yamagoya/jmdictdb/
RUN git clone https://github.com/oknj/textsearch_ja.git
RUN git clone https://github.com/WinteryFox/JibikiJLPTClassifier.git
RUN git clone https://github.com/WinteryFox/KanjidicParser.git
RUN git clone https://github.com/WinteryFox/TatoebaPostgreSQL.git

WORKDIR /var/jibiki_deps/jmdictdb
RUN /etc/init.d/postgresql restart && make init && /etc/init.d/postgresql stop
RUN python3 -m venv jmdictvenv
RUN /etc/init.d/postgresql restart && /bin/bash -c "source jmdictvenv/bin/activate" && python3 -m pip install -Iv psycopg2==2.7.3.2 && make loadall && make activate DBACT=jibiki && /etc/init.d/postgresql stop

WORKDIR /var/jibiki_deps/textsearch_ja
RUN make
RUN make install

WORKDIR /var/jibiki_deps/TatoebaPostgreSQL
RUN mvn package
WORKDIR /var/jibiki_deps/TatoebaPostgreSQL/target
COPY ./tatoeba.exp .
RUN chmod +x ./tatoeba.exp
RUN /etc/init.d/postgresql restart && ./tatoeba.exp && /etc/init.d/postgresql stop

WORKDIR /var/jibiki_deps/KanjidicParser
RUN curl -L https://github.com/WinteryFox/KanjidicParser/releases/download/1.0.0/kanjidicparser.jar --output kanjidicparser.jar
RUN /etc/init.d/postgresql restart && java -jar ./kanjidicparser.jar localhost jibiki postgres postgres && /etc/init.d/postgresql stop

WORKDIR /var/jibiki_deps/JibikiJLPTClassifier
RUN mvn package
WORKDIR /var/jibiki_deps/JibikiJLPTClassifier/target
COPY ./jlptclassifier.exp .
RUN chmod +x ./jlptclassifier.exp
RUN /etc/init.d/postgresql restart && ./jlptclassifier.exp && /etc/init.d/postgresql stop

USER postgres
CMD /usr/lib/postgresql/11/bin/postgres -D /var/lib/postgresql/11/main -c config_file=/etc/postgresql/11/main/postgresql.conf

