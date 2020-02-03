FROM ubuntu:20.04 as DbBuilder

ENV DEBIAN_FRONTEND=noninteractive

COPY ./build.sh /var/jibiki_deps/scripts/
COPY ./tatoeba.exp /var/jibiki_deps/scripts/
COPY ./jlptclassifier.exp /var/jibiki_deps/scripts/
COPY ./tables_readonly.sql /var/jibiki_deps/scripts/

RUN sh /var/jibiki_deps/scripts/build.sh

USER postgres
CMD ["/usr/lib/postgresql/12/bin/postgres", "-D", "/var/lib/postgresql/12/main", "-c", "config_file=/etc/postgresql/12/main/postgresql.conf"]

