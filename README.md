# MedIR

MedIR is a full information retrieval system for clinical medical literature. It builds an inverted index (with positional information) over a collection of PubMed Central articles and exposes retrieval through four interfaces: an interactive CLI, a desktop GUI, a REST API with a browser-based search UI, and a batch evaluation pipeline.

**Ranking models:** VSM (tf·idf cosine similarity) and BM25  
**Advanced features:** phrase queries, Rocchio pseudo-relevance feedback, spelling correction, autocomplete, document similarity, snippet highlighting

Built on the TREC Clinical Decision Support track dataset — three query types: diagnosis, test, treatment.

---

## Requirements

- Java 11+
- `src/libs/BioReader.jar` and `src/libs/Stemmer.jar` (included)

## Build

From the project root:

```bash
javac -cp "src/libs/BioReader.jar;src/libs/Stemmer.jar" -d out \
  src/indexer/Indexer.java src/queryeval/*.java
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

Reads `.nxml` files from `dataset/clinic/` and writes the inverted index (vocabulary, postings with positions, documents) to `CollectionIndex/`.

**Query — CLI**

```bash
java -jar dist/queryevaluator.jar [--model vsm|bm25] [--topk N] [--type diagnosis|test|treatment] [--snippet]
```

- Default model is BM25.
- Prefix query with `:` to show snippets.
- Use `"quotes"` in the query for exact phrase matching (positional index).
- Prefix with `+` to trigger Rocchio query expansion.

**Query — GUI**

```bash
java -jar dist/queryevaluatorgui.jar
```

- Choose model (BM25 / VSM) and type from the toolbar.
- Check **Expand** for Rocchio pseudo-relevance feedback.
- Phrase queries with `"quotes"` are supported.
- Results show rank, score, document type, and a highlighted snippet.
- Click any result to view the full title, abstract (with highlights), and metadata.

**REST API + Web UI**

```bash
java -jar dist/server.jar
```

Starts an HTTP server at `http://localhost:8080`. Open in a browser for the full search UI, or call the API directly.

| Endpoint | Description |
|----------|-------------|
| `GET /search?q=...&type=...&model=...&topk=...&expand=true` | Full-text search with BM25 or VSM |
| `GET /suggest?prefix=...` | Autocomplete — vocabulary terms by prefix, sorted by df |
| `GET /similar?pmcid=...&topk=N` | Find documents similar to a given article |
| `GET /stats` | Index statistics (vocabulary size, doc count, top terms) |

Web UI features: autocomplete dropdown, highlighted snippets (`<mark>`), query expansion indicator, "did you mean?" for OOV terms, similar-docs modal, document type badges, and export to CSV.

**Evaluate retrieval quality**

```bash
java -jar dist/evaluator.jar [--model vsm|bm25|both]
```

Default is `both` — runs VSM and BM25 side-by-side. Outputs to `doc/`:
- `eval_results.txt` — per-topic metrics (TSV)
- `results.txt` — TREC run format
- `qrels.txt` — relevance judgments (TREC format)

---

## Advanced Features

### Phrase Queries
Wrap terms in double quotes to require exact consecutive positions: `"chest pain"`. Implemented using the positional inverted index — matching documents must contain the complete sequence at adjacent token offsets.

### Rocchio Query Expansion
Pseudo-relevance feedback: the top-3 retrieved documents are used as feedback. Terms are scored by `tf × IDF` across the feedback set; the top-5 unseen terms are appended to the original query before re-ranking. Enabled with `+` in CLI, **Expand** checkbox in GUI/web.

### Spelling Correction (OOV handling)
Any query stem not found in the vocabulary is matched against known vocabulary stems using edit distance (Levenshtein ≤ 2, constrained by shared prefix for efficiency). Suggestions are substituted transparently during search and exposed in the API as `oov` field.

### Autocomplete
`/suggest?prefix=<prefix>` returns up to 8 vocabulary terms starting with that prefix, ranked by document frequency. The web UI calls this on every keystroke (200 ms debounce) and renders a dropdown with keyboard navigation.

### Document Similarity
`/similar?pmcid=<id>` extracts terms from the target document's title and abstract, filters to informative terms (df > 1 and df < N), and runs a BM25 search excluding the source document.

### Design Pattern — Proxy
`CachingQueryEngineProxy` wraps `RealQueryEngine` (which implements `IQueryEngine`) and memoises search results by `(stems, type, topK)` key. Used during batch evaluation to avoid redundant I/O when the same topic is evaluated across multiple models.

---

## Structure

```
src/
  indexer/        inverted index builder (positional, doc-length aware)
  queryeval/      VSM, BM25, phrase search, Rocchio, GUI, REST API, IR evaluation
  libs/           BioReader.jar, Stemmer.jar
dist/             compiled jars
doc/              evaluation output
Stopwords/        English and Greek stopword lists
CollectionIndex/  generated index files (created by indexer)
dataset/          MiniCollection — 54 documents, 6 topics
topics.xml        TREC-style topic definitions
```

---

## Results (MiniCollection, top-10)

| Model | MAP    | P@10   | NDCG@10 | R-Prec |
|-------|--------|--------|---------|--------|
| VSM   | 0.7268 | 0.5167 | 0.8043  | 0.7083 |
| BM25  | 0.5509 | 0.4667 | 0.6987  | 0.5417 |

VSM outperforms BM25 on this small collection (54 docs). BM25's length normalisation penalises the longer, more relevant clinical articles. On larger corpora the gap typically closes or reverses.
