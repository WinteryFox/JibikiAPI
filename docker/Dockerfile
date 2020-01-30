### Grab postgres image ###
FROM postgres

### Environment variables ###
ARG user=postgres
ARG database=jibiki
ARG password=12345
ENV POSTGRES_USER ${user}
ENV POSTGRES_DB ${database}
ENV POSTGRES_PASSWORD ${password}

### Import dump ###
COPY dump.sql /docker-entrypoint-initdb.d/