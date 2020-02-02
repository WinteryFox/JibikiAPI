# Jibiki Readonly Database Docker Image

Build by running `docker build -t winteryfox/jibiki_readonly --shm-size=4g .` in this directory.

Run from this directory with `docker run -p PORT_HERE:5432 winteryfox/jibiki_readonly`, replacing PORT_HERE with the port you want postgres to be available on.
