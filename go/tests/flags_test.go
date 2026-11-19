package tests

import "flag"

var rowCount = flag.Int("rows", 100_000, "rows written by BenchmarkWrite and read by BenchmarkRead")
