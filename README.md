# MedIR

MedIR is a full information retrieval system for clinical medical literature. It builds an inverted index over a collection of PubMed Central articles, ranks documents using both **VSM** (tf·idf cosine similarity) and **BM25**, and evaluates retrieval quality with standard IR metrics.

Built on the TREC Clinical Decision Support track dataset, it supports three query types (diagnosis, test, treatment) and ships with a desktop GUI, an interactive CLI, a REST API with a browser-based search UI, and a batch evaluation pipeline that compares VSM and BM25 side-by-side.

---

## Requirements

- Java 11+
- `src/libs/BioReader.jar` and `src/libs/Stemmer.jar` (included)

## Build

From the project root:

```bash
javac -cp "src/libs/BioReader.jar;src/libs/Stemmer.jar" -d out src/indexer/Indexer.java src/queryeval/*.java
```

Package jars:

```bash
jar cfm dist/indexer.jar           src/manifest_indexer.txt   -C out .
jar cfm dist/queryevaluator.jar    src/manifest_query.txt     -C out .
jar cfm dist/queryevaluatorgui.jar src/manifest_querygui.txt  -C out .
jar cfm dist/evaluator.jar         src/manifest_evaluator.txt -C out .
jar cfm dist/server.jar            src/manifest_server.txt    -C out .
```

Pre-built jars are already in `dist/`.

---

## Usage

**Build the index**

```bash
java -jar dist/indexer.jar
```

Reads `.nxml` files from `dataset/clinic/` and writes the inverted index to `CollectionIndex/`.

**Query (CLI)**

```bash
java -jar dist/queryevaluator.jar [--model vsm|bm25] [--topk N] [--type diagnosis|test|treatment] [--snippet]
```

Default model is BM25. Prefix query with `:` to show a snippet.

**Query (GUI)**

```bash
java -jar dist/queryevaluatorgui.jar
```

Switch between VSM and BM25 from the dropdown. Results show rank, score, and a snippet.

**REST API + Web UI**

```bash
java -jar dist/server.jar
```

Starts an HTTP server at `http://localhost:8080`. Open it in a browser for the search UI, or hit the API directly:

```
GET /search?q=chest+pain&type=diagnosis&model=bm25&topk=10
```

Returns JSON with ranked results, scores, snippets, and elapsed time.

**Evaluate retrieval quality**

```bash
java -jar dist/evaluator.jar [--model vsm|bm25|both]
```

Default is `both` — runs VSM and BM25 and prints a side-by-side comparison. Outputs to `doc/`:
- `eval_results.txt` — per-topic metrics (TSV)
- `results.txt` — TREC run format
- `qrels.txt` — relevance judgments (TREC format)

---

## Structure

```
src/
  indexer/        inverted index builder
  queryeval/      VSM + BM25 retrieval, GUI, REST API, IR evaluation
  libs/           BioReader.jar, Stemmer.jar
dist/             compiled jars
doc/              evaluation output
Stopwords/        English and Greek stopword lists
CollectionIndex/  generated index files (created by indexer)
dataset/          MiniCollection – 54 documents, 6 topics
topics.xml        TREC-style topic definitions
```

---

## Results (MiniCollection, top-10)

| Model | MAP    | P@10   | NDCG@10 | R-Prec |
|-------|--------|--------|---------|--------|
| VSM   | 0.7268 | 0.5167 | 0.8043  | 0.7083 |
| BM25  | 0.5509 | 0.4667 | 0.6987  | 0.5417 |

VSM outperforms BM25 on this small collection (54 docs). BM25's length normalization penalizes the longer, more relevant clinical articles. On larger corpora the gap typically closes or reverses.

Uses a **Proxy pattern** (`CachingQueryEngineProxy`) to cache search results across evaluation topics.
