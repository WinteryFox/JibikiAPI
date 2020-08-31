[![Jibiki](https://img.shields.io/discord/635150469664210996.svg?color=7289da&label=Jibiki&logo=discord&style=flat-square)](https://discord.gg/635150469664210996)
[![CircleCI](https://circleci.com/gh/WinteryFox/JibikiAPI.svg?style=svg)](https://circleci.com/gh/WinteryFox/JibikiAPI)

<img align="right" width=27% alt="Branding icon" src="https://jibiki.app/logo_circle.png">

# Jibiki

[Jibiki](https://jibiki.app/) is a fast and reliable open-source dictionary website and app,
this repository contains the API which is responsible for all functionality.
The front-end repository can be found at
[JibikiFrontend](https://github.com/WinteryFox/JibikiFrontend/).

A Java and Kotlin library to interact with the API can be found at
[Jibiki Java Client](https://github.com/ByteAlex/jibiki-java-client)

## Goals

* Build a comprehensive completely free API tailored to the needs of language learners
and developers
* Create an environment in which you can easily; bookmark words, save them to your own deck,
learn and review words in a single click.
* Assist in the understanding of complicated sentences by providing a tool that will break
down your sentence in a click and display information about every segment.

## Development environment set-up

### Windows
On Windows, you will need to install Windows Subsystems for Linux (WSL) in order to run
the PostgreSQL database Jibiki requires.

1. Enable WSL by going to the `Turn windows features on and off` settings tab
2. Go to the Microsoft Store and install Ubuntu 18.06 or any distrobution of your choosing
3. Start Ubuntu and create a new username and password
4. Run `apt update && apt upgrade -y`
5. Run `apt install build-essential -y`
6. Resume by following the database setup instructions below

### Database setup

1. Install PostgreSQL by running `apt install postgresql`
2. Create a database called jibiki by entering the PSQL shell utility with
`psql -U postgres` and then running `CREATE DATABASE jibiki;` then exit using `\q`
3. Clone [JMdictDB](https://gitlab.com/yamagoya/jmdictdb/) by running
`git clone https://gitlab.com/yamagoya/jmdictdb/`
4. `cd` into the JMDictDB directory
5. Run `make install`
6. Download and install [TatoebaPostgreSQL](https://github.com/WinteryFox/TatoebaPostgreSQL/),
see the README for install instructions.
9. Download and install [Kanjidic2Importer](https://github.com/WinteryFox/KanjidicParser/),
see the README for install instructions.

### Environment setup

1. Clone the [JibikiFrontend](https://github.com/WinteryFox/JibikiFrontend) repository and
follow the setup instructions in the README
2. Clone this repository
3. Run JibikiFrontend and JibikiAPI at the same time, the frontend can then be found at `localhost`
and the API will run at `localhost:8080`
