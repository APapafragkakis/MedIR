#!/usr/bin/env bash
# Build all MedIR jars (Linux / macOS)
set -euo pipefail
CP="src/libs/BioReader.jar:src/libs/Stemmer.jar"

mkdir -p out dist
echo "Compiling..."
javac -encoding UTF-8 -cp "$CP" -d out $(find src -name '*.java')

jar cfm dist/indexer.jar           src/manifest_indexer.txt   -C out .
jar cfm dist/queryevaluator.jar    src/manifest_query.txt     -C out .
jar cfm dist/queryevaluatorgui.jar src/manifest_querygui.txt  -C out .
jar cfm dist/evaluator.jar         src/manifest_evaluator.txt -C out .
jar cfm dist/server.jar            src/manifest_server.txt    -C out .

echo "Built jars in dist/  (run the indexer first, then any tool)."
