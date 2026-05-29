# MedIR

MedIR is a full information retrieval system for clinical medical literature. It builds an inverted index over a collection of PubMed Central articles, ranks documents using Vector Space Model (VSM) with tf·idf cosine similarity, and evaluates retrieval quality with standard IR metrics.

Built on the TREC Clinical Decision Support track dataset, it supports three query types (diagnosis, test, treatment) and includes a desktop GUI for interactive search alongside a batch evaluation pipeline that outputs MAP, NDCG, P@K, R@K, and R-Precision scores.

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
java -jar dist/queryevaluator.jar
```

Flags: `--topk N`, `--type diagnosis|test|treatment`, `--snippet`, `--topics topics.xml`

**Query (GUI)**

```bash
java -jar dist/queryevaluatorgui.jar
```

**Evaluate retrieval quality**

```bash
java -jar dist/evaluator.jar
```

Outputs to `doc/`:
- `eval_results.txt` — per-topic metrics (TSV)
- `results.txt` — TREC run format
- `qrels.txt` — relevance judgments (TREC format)

---

## Structure

```
src/
  indexer/        inverted index builder
  queryeval/      VSM retrieval, GUI, IR evaluation
  libs/           BioReader.jar, Stemmer.jar
dist/             compiled jars
doc/              evaluation output (eval_results.txt, results.txt, qrels.txt)
Stopwords/        English and Greek stopword lists
CollectionIndex/  generated index files (created by indexer)
dataset/          MiniCollection – 54 documents, 6 topics
topics.xml        TREC-style topic definitions
```

---

## Results (MiniCollection, top-10)

| MAP    | P@10   | NDCG@10 | R-Prec |
|--------|--------|---------|--------|
| 0.7268 | 0.5167 | 0.8043  | 0.7083 |

Uses a **Proxy pattern** (`CachingQueryEngineProxy`) to cache VSM search results across topics.
