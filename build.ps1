# Build all MedIR jars (Windows / PowerShell)
$ErrorActionPreference = "Stop"
$cp = "src/libs/BioReader.jar;src/libs/Stemmer.jar"

New-Item -ItemType Directory -Force -Path out, dist | Out-Null

$sources = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files..."
javac -encoding UTF-8 -cp $cp -d out $sources

jar cfm dist/indexer.jar           src/manifest_indexer.txt   -C out .
jar cfm dist/queryevaluator.jar    src/manifest_query.txt     -C out .
jar cfm dist/queryevaluatorgui.jar src/manifest_querygui.txt  -C out .
jar cfm dist/evaluator.jar         src/manifest_evaluator.txt -C out .
jar cfm dist/server.jar            src/manifest_server.txt    -C out .

Write-Host "Built jars in dist/  (run the indexer first, then any tool)."
