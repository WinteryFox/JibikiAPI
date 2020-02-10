# Jibiki Database Docker Image

This is the Dockerfile that builds the entire database for all Jibiki systems,
the image is publicly available with releases and should not be built on your own.

### How to use

First pull the image from Docker hub by running
`docker pull jibiki:latest` WIP

#### Building this image yourself (not recommended)

Build by running `docker build -t winteryfox/jibiki_readonly --shm-size=4g .` in this directory.

Run from this directory with `docker run -p PORT_HERE:5432 winteryfox/jibiki_readonly`,
replacing PORT_HERE with the port you want postgres to be available on.