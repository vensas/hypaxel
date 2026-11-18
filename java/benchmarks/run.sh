#!/bin/sh
# Stopwatch and peak-memory runs for large row counts. Usage: ./run.sh [rows]  (default 1,000,000)
#
# "time" runs hold the rows in memory and report the mean of 5 runs after 2 warm-ups plus the
# bytes the writing thread allocated per run. "once" runs create rows on demand in a fresh JVM
# with default settings, so the process's peak resident memory reflects the writer; BASELINE
# only creates the rows.
set -eu
cd "$(dirname "$0")"
ROWS="${1:-1000000}"
JAR=target/benchmarks.jar
VARIANTS="HYPAXEL_TYPED HYPAXEL_ROW_WRITER HYPAXEL_OBJECTS HYPAXEL_BOXED_ROWS HYPAXEL_SHARED_STRINGS HYPAXEL_LEVEL_6 FASTEXCEL FASTEXCEL_LEVEL_1 POI_SXSSF"

if [ "$(uname)" = "Darwin" ]; then
  TIME_FLAG=-l
else
  TIME_FLAG=-v
fi

for variant in $VARIANTS; do
  java -jar "$JAR" time "$variant" "$ROWS"
done

for variant in BASELINE $VARIANTS; do
  /usr/bin/time "$TIME_FLAG" java -jar "$JAR" once "$variant" "$ROWS" 2>&1 | grep -E "rows=|maximum resident|Maximum resident"
done
