#!/bin/bash
#
# Creates the lake's database beside Aran's, in the same Postgres.
#
# The default topology runs two Postgres instances — one for Aran, one inside the
# lake's own compose file. For a single-node install that is a whole extra service,
# an extra volume and an extra thing to back up, for two databases that never share
# a transaction. Postgres separates them perfectly well.
#
# Runs once, on an empty data directory, via the postgres image's initdb hook.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE aran_lake OWNER $POSTGRES_USER;
EOSQL
