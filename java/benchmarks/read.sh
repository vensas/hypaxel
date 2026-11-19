#!/bin/sh
# Read benchmark: the write benchmark's ten columns, written by Hypaxel with inline and with shared
# strings, read cell by cell by each reader. Usage: ./read.sh [rows] [directory]
#
# "readtime" runs report the mean and best of 5 runs after 2 warm-ups plus the bytes the reading
# thread allocated per run; every run must produce the same checksum. "read" runs are a single
# cold read in a fresh JVM with default settings, for the process's peak resident memory.
set -eu
cd "$(dirname "$0")"
ROWS="${1:-1000000}"
DIR="${2:-target/read-data}"
JAR=target/benchmarks.jar
READERS="HYPAXEL FASTEXCEL POI_SAX"

if [ "$(uname)" = "Darwin" ]; then
  TIME_FLAG=-l
else
  TIME_FLAG=-v
fi

mkdir -p "$DIR"
for storage in inline shared; do
  if [ ! -f "$DIR/$storage-$ROWS.xlsx" ]; then
    java -jar "$JAR" generate "$DIR/$storage-$ROWS.xlsx" "$ROWS" "$storage"
  fi
done

for storage in inline shared; do
  for reader in $READERS; do
    java -jar "$JAR" readtime "$reader" "$DIR/$storage-$ROWS.xlsx"
  done
done

for storage in inline shared; do
  for reader in $READERS; do
    /usr/bin/time "$TIME_FLAG" java -jar "$JAR" read "$reader" "$DIR/$storage-$ROWS.xlsx" 2>&1 | grep -E "file=|maximum resident|Maximum resident"
  done
done
