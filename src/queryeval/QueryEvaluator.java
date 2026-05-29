package queryeval;

import gr.uoc.csd.hy463.NXMLFileReader;
import gr.uoc.csd.hy463.Topic;
import gr.uoc.csd.hy463.TopicsReader;
import mitos.stemmer.Stemmer;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class QueryEvaluator {

    static String  INDEX_DIR    = "CollectionIndex";
    static String  STOPWORDS_EN = "Stopwords/stopwordsEn.txt";
    static String  STOPWORDS_GR = "Stopwords/stopwordsGr.txt";
    static int     TOP_K        = 10;
    static boolean SHOW_SNIPPET = false;
    static String  TYPE_FILTER  = null;

    static final Map<String, VocabEntry>   vocabulary  = new HashMap<>();
    static final Map<String, List<String>> stemToWords = new HashMap<>();
    static final Set<String>               stopwords   = new HashSet<>();
    static int    totalDocs = 0;
    static double avgDocLen = 0;
    static final Map<Integer, Integer> docLengths = new HashMap<>();
    static String MODEL = "bm25";

    static final double BM25_K1 = 1.5;
    static final double BM25_B  = 0.75;
    static final int ROCCHIO_FEEDBACK_DOCS = 3;
    static final int ROCCHIO_EXPAND_TERMS  = 5;

    static class VocabEntry {
        final String stem;
        final int    df;
        final long   pointer;
        VocabEntry(String stem, int df, long pointer) {
            this.stem = stem; this.df = df; this.pointer = pointer;
        }
    }

    static class Result implements Comparable<Result> {
        final String path;
        final String pmcid;
        final double score;
        Result(String path, String pmcid, double score) {
            this.path = path; this.pmcid = pmcid; this.score = score;
        }
        @Override public int compareTo(Result o) { return Double.compare(o.score, this.score); }
    }

    static class DocPositions {
        final long          docOffset;
        final List<Integer> positions;
        DocPositions(long offset, List<Integer> positions) {
            this.docOffset = offset; this.positions = positions;
        }
    }

    static String resolveBaseDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("Stopwords").resolve("stopwordsEn.txt"))) return cwd.toString();
        try {
            Path src = Paths.get(
                QueryEvaluator.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath();
            for (Path p = src; p != null; p = p.getParent()) {
                if (Files.exists(p.resolve("Stopwords").resolve("stopwordsEn.txt"))) return p.toString();
            }
        } catch (URISyntaxException ignored) {}
        return cwd.toString();
    }

    public static void main(String[] args) throws Exception {
        Stemmer.Initialize();

        String base = resolveBaseDir();
        INDEX_DIR    = base + File.separator + "CollectionIndex";
        STOPWORDS_EN = base + File.separator + "Stopwords" + File.separator + "stopwordsEn.txt";
        STOPWORDS_GR = base + File.separator + "Stopwords" + File.separator + "stopwordsGr.txt";

        String mode       = "interactive";
        String topicsFile = null;
        String useField   = "summary";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index":   INDEX_DIR    = args[++i];                   break;
                case "--topics":  mode = "topics"; topicsFile = args[++i];    break;
                case "--field":   useField     = args[++i];                   break;
                case "--topk":    TOP_K        = Integer.parseInt(args[++i]); break;
                case "--snippet": SHOW_SNIPPET = true;                        break;
                case "--type":    TYPE_FILTER  = args[++i];                   break;
                case "--model":   MODEL        = args[++i];                   break;
            }
        }

        loadStopwords(STOPWORDS_EN);
        loadStopwords(STOPWORDS_GR);
        loadVocabulary();
        countDocuments();

        System.out.println("Index loaded: " + vocabulary.size() + " terms | " + totalDocs + " documents");

        if ("topics".equals(mode)) processTopics(topicsFile, useField);
        else                       interactiveMode();
    }

    static void loadStopwords(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) stopwords.add(line.trim().toLowerCase());
        }
    }

    static void loadVocabulary() throws IOException {
        File f = new File(INDEX_DIR + File.separator + "VocabularyFile.txt");
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(" \\| ");
                if (p.length < 4) continue;
                String word    = p[0].trim();
                String stem    = p[1].trim();
                int    df      = Integer.parseInt(p[2].trim());
                long   pointer = Long.parseLong(p[3].trim());
                vocabulary.put(word, new VocabEntry(stem, df, pointer));
                stemToWords.computeIfAbsent(stem, k -> new ArrayList<>()).add(word);
            }
        }
    }

    static void countDocuments() throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(INDEX_DIR + File.separator + "DocumentsFile.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                totalDocs++;
                String[] p = line.split(" \\| ");
                if (p.length >= 4) {
                    int docNum = Integer.parseInt(p[0].trim());
                    int len    = Integer.parseInt(p[3].trim());
                    docLengths.put(docNum, len);
                    avgDocLen += len;
                }
            }
        }
        if (totalDocs > 0) avgDocLen /= totalDocs;
    }

    static List<String> processQuery(String text) {
        String[] tokens = text.toLowerCase()
                              .replaceAll("[^\\p{L}\\p{Nd} ]+", " ")
                              .split("\\s+");
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty() || stopwords.contains(token) || token.matches("\\d+")) continue;
            String stem = Stemmer.Stem(token);
            if (!stem.isEmpty()) result.add(stem);
        }
        return result;
    }

    static List<String> extractPhrases(String text, List<List<String>> phrases) {
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            List<String> phraseStems = processQuery(m.group(1));
            if (phraseStems.size() > 1) phrases.add(phraseStems);
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        return processQuery(sb.toString());
    }

    static int editDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++)
            for (int j = 1; j <= lb; j++)
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1]
                        : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
        return dp[la][lb];
    }

    static List<String> suggestOOV(String stem) {
        List<String> suggestions = new ArrayList<>();
        if (stem.length() < 3) return suggestions;
        String prefix = stem.substring(0, 3);
        TreeMap<Integer, List<String>> ranked = new TreeMap<>();
        for (String vocabStem : stemToWords.keySet()) {
            if (!vocabStem.startsWith(prefix)) continue;
            int d = editDistance(stem, vocabStem);
            if (d <= 2) ranked.computeIfAbsent(d, k -> new ArrayList<>()).add(vocabStem);
        }
        for (List<String> bucket : ranked.values()) {
            for (String s : bucket) {
                suggestions.add(s);
                if (suggestions.size() >= 3) return suggestions;
            }
        }
        return suggestions;
    }

    static Map<String, List<String>> getOovInfo(List<String> stems) {
        Map<String, List<String>> oov = new LinkedHashMap<>();
        for (String stem : stems) {
            if (!stemToWords.containsKey(stem)) {
                List<String> s = suggestOOV(stem);
                if (!s.isEmpty()) oov.put(stem, s);
            }
        }
        return oov;
    }

    static List<Result> searchVSM(List<String> queryStems, int topK) throws IOException {
        if (queryStems.isEmpty()) return Collections.emptyList();

        Map<String, Integer> queryTF  = new LinkedHashMap<>();
        for (String s : queryStems) queryTF.merge(s, 1, Integer::sum);

        Map<Integer, Double> scores    = new HashMap<>();
        Map<Integer, Long>   docOffset = new HashMap<>();

        try (RandomAccessFile postRaf = new RandomAccessFile(
                     INDEX_DIR + File.separator + "PostingFile.txt",  "r");
             RandomAccessFile docsRaf = new RandomAccessFile(
                     INDEX_DIR + File.separator + "DocumentsFile.txt", "r")) {

            for (Map.Entry<String, Integer> qe : queryTF.entrySet()) {
                String queryStem = qe.getKey();
                int    qtf       = qe.getValue();

                List<String> words = stemToWords.getOrDefault(queryStem, Collections.emptyList());
                if (words.isEmpty()) {
                    List<String> sug = suggestOOV(queryStem);
                    if (!sug.isEmpty()) {
                        List<String> merged = new ArrayList<>();
                        for (String s : sug) merged.addAll(stemToWords.getOrDefault(s, Collections.emptyList()));
                        words = merged;
                    }
                }

                for (String word : words) {
                    VocabEntry entry = vocabulary.get(word);
                    if (entry == null || entry.df == 0) continue;
                    double idf     = Math.log((double) totalDocs / entry.df);
                    double qWeight = qtf * idf;
                    postRaf.seek(entry.pointer);
                    for (int i = 0; i < entry.df; i++) {
                        String postLine = postRaf.readLine();
                        if (postLine == null) break;
                        String[] p = postLine.split(" \\| ");
                        if (p.length < 4) continue;
                        int  docNum = Integer.parseInt(p[0].trim());
                        int  tf     = Integer.parseInt(p[1].trim());
                        long dOff   = Long.parseLong(p[3].trim());
                        scores.merge(docNum, qWeight * (tf * idf), Double::sum);
                        docOffset.putIfAbsent(docNum, dOff);
                    }
                }
            }

            List<Result> results = new ArrayList<>();
            for (Map.Entry<Integer, Double> se : scores.entrySet()) {
                Long off = docOffset.get(se.getKey());
                if (off == null) continue;
                docsRaf.seek(off);
                String docLine = docsRaf.readLine();
                if (docLine == null) continue;
                String[] dp = docLine.split(" \\| ");
                if (dp.length < 3) continue;
                String path  = dp[1].trim();
                double norm  = Double.parseDouble(dp[2].trim());
                String pmcid = new File(path).getName().replace(".nxml", "");
                double finalScore = norm > 0 ? se.getValue() / norm : 0;
                results.add(new Result(path, pmcid, finalScore));
            }
            results.sort(null);
            return results.subList(0, Math.min(topK, results.size()));
        }
    }

    static List<Result> searchBM25(List<String> queryStems, int topK) throws IOException {
        if (queryStems.isEmpty()) return Collections.emptyList();

        Map<String, Integer> queryTF = new LinkedHashMap<>();
        for (String s : queryStems) queryTF.merge(s, 1, Integer::sum);

        Map<Integer, Double> scores    = new HashMap<>();
        Map<Integer, Long>   docOffset = new HashMap<>();

        try (RandomAccessFile postRaf = new RandomAccessFile(
                     INDEX_DIR + File.separator + "PostingFile.txt",  "r");
             RandomAccessFile docsRaf = new RandomAccessFile(
                     INDEX_DIR + File.separator + "DocumentsFile.txt", "r")) {

            for (Map.Entry<String, Integer> qe : queryTF.entrySet()) {
                String queryStem = qe.getKey();

                List<String> words = stemToWords.getOrDefault(queryStem, Collections.emptyList());
                if (words.isEmpty()) {
                    List<String> sug = suggestOOV(queryStem);
                    if (!sug.isEmpty()) {
                        List<String> merged = new ArrayList<>();
                        for (String s : sug) merged.addAll(stemToWords.getOrDefault(s, Collections.emptyList()));
                        words = merged;
                    }
                }

                for (String word : words) {
                    VocabEntry entry = vocabulary.get(word);
                    if (entry == null || entry.df == 0) continue;
                    double idf = Math.log((totalDocs - entry.df + 0.5) / (entry.df + 0.5) + 1.0);
                    postRaf.seek(entry.pointer);
                    for (int i = 0; i < entry.df; i++) {
                        String postLine = postRaf.readLine();
                        if (postLine == null) break;
                        String[] p = postLine.split(" \\| ");
                        if (p.length < 4) continue;
                        int  docNum = Integer.parseInt(p[0].trim());
                        int  tf     = Integer.parseInt(p[1].trim());
                        long dOff   = Long.parseLong(p[3].trim());
                        double dl     = docLengths.getOrDefault(docNum, (int) Math.max(1, avgDocLen));
                        double tfNorm = tf * (BM25_K1 + 1) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / Math.max(1, avgDocLen)));
                        scores.merge(docNum, idf * tfNorm, Double::sum);
                        docOffset.putIfAbsent(docNum, dOff);
                    }
                }
            }

            List<Result> results = new ArrayList<>();
            for (Map.Entry<Integer, Double> se : scores.entrySet()) {
                Long off = docOffset.get(se.getKey());
                if (off == null) continue;
                docsRaf.seek(off);
                String docLine = docsRaf.readLine();
                if (docLine == null) continue;
                String[] dp = docLine.split(" \\| ");
                if (dp.length < 2) continue;
                String path  = dp[1].trim();
                String pmcid = new File(path).getName().replace(".nxml", "");
                results.add(new Result(path, pmcid, se.getValue()));
            }
            results.sort(null);
            return results.subList(0, Math.min(topK, results.size()));
        }
    }

    static List<Result> search(List<String> queryStems, int topK) throws IOException {
        return "bm25".equalsIgnoreCase(MODEL)
            ? searchBM25(queryStems, topK)
            : searchVSM(queryStems, topK);
    }

    static Map<Integer, DocPositions> loadStemPostings(String stem) throws IOException {
        Map<Integer, DocPositions> result = new HashMap<>();
        List<String> words = stemToWords.getOrDefault(stem, Collections.emptyList());
        if (words.isEmpty()) return result;
        try (RandomAccessFile raf = new RandomAccessFile(
                INDEX_DIR + File.separator + "PostingFile.txt", "r")) {
            for (String word : words) {
                VocabEntry entry = vocabulary.get(word);
                if (entry == null) continue;
                raf.seek(entry.pointer);
                for (int i = 0; i < entry.df; i++) {
                    String line = raf.readLine();
                    if (line == null) break;
                    String[] p = line.split(" \\| ");
                    if (p.length < 4) continue;
                    int docId = Integer.parseInt(p[0].trim());
                    List<Integer> positions = new ArrayList<>();
                    for (String pos : p[2].trim().split(",")) {
                        try { positions.add(Integer.parseInt(pos.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                    long offset = Long.parseLong(p[3].trim());
                    if (!result.containsKey(docId))
                        result.put(docId, new DocPositions(offset, new ArrayList<>(positions)));
                    else
                        result.get(docId).positions.addAll(positions);
                }
            }
        }
        return result;
    }

    static List<Result> searchPhrase(List<String> phraseStems, int topK) throws IOException {
        if (phraseStems.isEmpty()) return Collections.emptyList();
        if (phraseStems.size() == 1) return search(phraseStems, topK);

        List<Map<Integer, DocPositions>> allPostings = new ArrayList<>();
        for (String stem : phraseStems) allPostings.add(loadStemPostings(stem));

        Set<Integer> candidates = new HashSet<>(allPostings.get(0).keySet());
        for (int i = 1; i < allPostings.size(); i++) candidates.retainAll(allPostings.get(i).keySet());

        List<Result> results = new ArrayList<>();
        try (RandomAccessFile docsRaf = new RandomAccessFile(
                INDEX_DIR + File.separator + "DocumentsFile.txt", "r")) {
            for (int docId : candidates) {
                List<Integer> firstPos = new ArrayList<>(allPostings.get(0).get(docId).positions);
                Collections.sort(firstPos);
                int phraseCount = 0;
                for (int startPos : firstPos) {
                    boolean match = true;
                    for (int k = 1; k < phraseStems.size(); k++) {
                        if (!allPostings.get(k).get(docId).positions.contains(startPos + k)) {
                            match = false; break;
                        }
                    }
                    if (match) phraseCount++;
                }
                if (phraseCount == 0) continue;

                long offset = allPostings.get(0).get(docId).docOffset;
                docsRaf.seek(offset);
                String docLine = docsRaf.readLine();
                if (docLine == null) continue;
                String[] dp = docLine.split(" \\| ");
                if (dp.length < 2) continue;
                String path  = dp[1].trim();
                String pmcid = new File(path).getName().replace(".nxml", "");
                results.add(new Result(path, pmcid, phraseCount));
            }
        }
        results.sort(null);
        return results.subList(0, Math.min(topK, results.size()));
    }

    static List<String> expandQueryRocchio(List<String> originalStems,
                                           int feedbackDocs, int expandTerms) {
        try {
            List<Result> feedback = searchBM25(originalStems, feedbackDocs);
            if (feedback.isEmpty()) return originalStems;

            Map<String, Double> termScores = new HashMap<>();
            for (Result r : feedback) {
                try {
                    NXMLFileReader reader = new NXMLFileReader(new File(r.path));
                    String text = (reader.getTitle()  != null ? reader.getTitle()  : "")
                                + " "
                                + (reader.getAbstr() != null ? reader.getAbstr() : "");
                    List<String> docStems = processQuery(text);
                    Map<String, Long> tf  = docStems.stream()
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
                    for (Map.Entry<String, Long> e : tf.entrySet()) {
                        String stem = e.getKey();
                        List<String> words = stemToWords.getOrDefault(stem, Collections.emptyList());
                        if (words.isEmpty()) continue;
                        VocabEntry ve = vocabulary.get(words.get(0));
                        if (ve == null || ve.df == 0) continue;
                        double idf = Math.log((totalDocs - ve.df + 0.5) / (ve.df + 0.5) + 1.0);
                        termScores.merge(stem, e.getValue() * idf / feedbackDocs, Double::sum);
                    }
                } catch (Exception ignored) {}
            }

            Set<String> original = new HashSet<>(originalStems);
            List<String> newTerms = termScores.entrySet().stream()
                .filter(e -> !original.contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(expandTerms)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            List<String> expanded = new ArrayList<>(originalStems);
            expanded.addAll(newTerms);
            return expanded;
        } catch (Exception e) {
            return originalStems;
        }
    }

    static String getSnippet(String docPath, List<String> queryStems) {
        try {
            NXMLFileReader reader = new NXMLFileReader(new File(docPath));
            String[] sources = { reader.getTitle(), reader.getAbstr(), reader.getBody() };
            for (String src : sources) {
                if (src == null || src.isEmpty()) continue;
                String[] words = src.split("\\s+");
                for (int i = 0; i < words.length; i++) {
                    String cleaned = words[i].toLowerCase().replaceAll("[^\\p{L}\\p{Nd}]", "");
                    if (cleaned.isEmpty()) continue;
                    if (queryStems.contains(Stemmer.Stem(cleaned))) {
                        int s = Math.max(0, i - 8);
                        int e = Math.min(words.length, i + 12);
                        StringBuilder sb = new StringBuilder(s > 0 ? "..." : "");
                        for (int j = s; j < e; j++) sb.append(words[j]).append(' ');
                        if (e < words.length) sb.append("...");
                        return sb.toString().trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "(no snippet)";
    }

    static String getHighlightedSnippet(String docPath, List<String> queryStems) {
        try {
            NXMLFileReader reader = new NXMLFileReader(new File(docPath));
            String[] sources = { reader.getTitle(), reader.getAbstr(), reader.getBody() };
            for (String src : sources) {
                if (src == null || src.isEmpty()) continue;
                String[] words = src.split("\\s+");
                for (int i = 0; i < words.length; i++) {
                    String cleaned = words[i].toLowerCase().replaceAll("[^\\p{L}\\p{Nd}]", "");
                    if (cleaned.isEmpty()) continue;
                    if (queryStems.contains(Stemmer.Stem(cleaned))) {
                        int s = Math.max(0, i - 8);
                        int e = Math.min(words.length, i + 12);
                        StringBuilder sb = new StringBuilder(s > 0 ? "... " : "");
                        for (int j = s; j < e; j++) {
                            String w  = words[j];
                            String wc = w.toLowerCase().replaceAll("[^\\p{L}\\p{Nd}]", "");
                            String ws = wc.isEmpty() ? "" : Stemmer.Stem(wc);
                            if (!ws.isEmpty() && queryStems.contains(ws))
                                sb.append("<mark>").append(htmlEncode(w)).append("</mark>");
                            else
                                sb.append(htmlEncode(w));
                            if (j < e - 1) sb.append(' ');
                        }
                        if (e < words.length) sb.append(" ...");
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "(no snippet)";
    }

    static String htmlEncode(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static List<String> getAutocompleteSuggestions(String prefix, int topK) {
        if (prefix == null || prefix.length() < 2) return Collections.emptyList();
        String p = prefix.toLowerCase();
        return vocabulary.entrySet().stream()
            .filter(e -> e.getKey().startsWith(p))
            .sorted(Comparator.comparingInt((Map.Entry<String, VocabEntry> e) -> e.getValue().df).reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    static List<Result> getSimilarDocs(String pmcid, int topK) throws IOException {
        String docPath = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(INDEX_DIR + File.separator + "DocumentsFile.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(pmcid)) {
                    String[] p = line.split(" \\| ");
                    if (p.length >= 2) { docPath = p[1].trim(); break; }
                }
            }
        }
        if (docPath == null) return Collections.emptyList();
        try {
            NXMLFileReader reader = new NXMLFileReader(new File(docPath));
            String text = (reader.getTitle()  != null ? reader.getTitle()  : "")
                        + " "
                        + (reader.getAbstr() != null ? reader.getAbstr() : "");
            if (text.length() > 600) text = text.substring(0, 600);

            List<String> allStems = processQuery(text);
            Map<String, Long> tf  = allStems.stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            List<String> topStems = tf.entrySet().stream()
                .filter(e -> {
                    List<String> ws = stemToWords.getOrDefault(e.getKey(), Collections.emptyList());
                    if (ws.isEmpty()) return false;
                    VocabEntry ve = vocabulary.get(ws.get(0));
                    return ve != null && ve.df > 1 && ve.df < totalDocs;
                })
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            return searchBM25(topStems, topK + 1).stream()
                .filter(r -> !r.pmcid.equals(pmcid))
                .limit(topK)
                .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static List<Result> filterByType(List<Result> results, String type, int topK) {
        if (type == null || "all".equalsIgnoreCase(type))
            return results.subList(0, Math.min(topK, results.size()));
        String token    = File.separator + type.toLowerCase() + File.separator;
        String altToken = "/" + type.toLowerCase() + "/";
        List<Result> filtered = new ArrayList<>();
        for (Result r : results) {
            String p = r.path.toLowerCase();
            if (p.contains(token) || p.contains(altToken)) {
                filtered.add(r);
                if (filtered.size() == topK) break;
            }
        }
        return filtered;
    }

    static void interactiveMode() throws Exception {
        BufferedReader br  = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String         sep = "-".repeat(56);

        System.out.println("\nMedIR  [" + MODEL.toUpperCase() + "]");
        System.out.println("Phrase queries: use quotes, e.g.  \"chest pain\"");
        System.out.println("Query expansion: prefix with +,  e.g.  +myocardial");
        System.out.println("'exit' to quit, prefix ':' for snippet\n");

        while (true) {
            System.out.print("> ");
            String input = br.readLine();
            if (input == null || input.equalsIgnoreCase("exit")) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            boolean showSnip = SHOW_SNIPPET || input.startsWith(":");
            if (input.startsWith(":")) input = input.substring(1).trim();

            boolean doExpand = input.startsWith("+");
            if (doExpand) input = input.substring(1).trim();

            long t0 = System.currentTimeMillis();

            List<List<String>> phrases = new ArrayList<>();
            List<String>       stems   = extractPhrases(input, phrases);

            if (stems.isEmpty() && phrases.isEmpty()) {
                System.out.println("No indexable terms.\n");
                continue;
            }

            Map<String, List<String>> oov = getOovInfo(stems);
            if (!oov.isEmpty()) {
                for (Map.Entry<String, List<String>> e : oov.entrySet())
                    System.out.println("  [OOV] \"" + e.getKey() + "\" -> suggestions: " + e.getValue());
            }

            List<String> expandedTerms = Collections.emptyList();
            if (doExpand && !stems.isEmpty()) {
                List<String> expanded = expandQueryRocchio(stems, ROCCHIO_FEEDBACK_DOCS, ROCCHIO_EXPAND_TERMS);
                expandedTerms = expanded.subList(stems.size(), expanded.size());
                stems = expanded;
                if (!expandedTerms.isEmpty())
                    System.out.println("  [expand] added: " + expandedTerms);
            }

            int          fetchK  = (TYPE_FILTER != null && !"all".equalsIgnoreCase(TYPE_FILTER)) ? TOP_K * 20 : TOP_K;
            List<Result> results = runCombinedSearch(stems, phrases, MODEL, fetchK);
            results = filterByType(results, TYPE_FILTER, TOP_K);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println();
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                System.out.printf("  %2d.  %-22s  %.5f%n", i + 1, r.pmcid, r.score);
                if (showSnip)
                    System.out.println("        " + getSnippet(r.path, stems) + "\n");
            }
            System.out.printf("%n  %d result(s) in %dms%n%s%n%n", results.size(), elapsed, sep);
        }
        System.out.println("Bye.");
    }

    static List<Result> runCombinedSearch(List<String> stems,
                                          List<List<String>> phrases,
                                          String model,
                                          int topK) throws IOException {
        if (phrases.isEmpty())
            return "bm25".equalsIgnoreCase(model) ? searchBM25(stems, topK) : searchVSM(stems, topK);

        Set<String> phraseMatchPmcids = null;
        for (List<String> phrase : phrases) {
            List<Result> pm = searchPhrase(phrase, totalDocs);
            Set<String> pmcids = pm.stream().map(r -> r.pmcid).collect(Collectors.toSet());
            if (phraseMatchPmcids == null) phraseMatchPmcids = new HashSet<>(pmcids);
            else phraseMatchPmcids.retainAll(pmcids);
        }

        if (stems.isEmpty()) {
            List<Result> phraseResults = new ArrayList<>();
            for (List<String> phrase : phrases) phraseResults.addAll(searchPhrase(phrase, topK));
            phraseResults.sort(null);
            return phraseResults.subList(0, Math.min(topK, phraseResults.size()));
        }

        List<Result> base = "bm25".equalsIgnoreCase(model)
            ? searchBM25(stems, topK * 5)
            : searchVSM(stems, topK * 5);

        final Set<String> matchSet = phraseMatchPmcids != null ? phraseMatchPmcids : Collections.emptySet();
        List<Result> filtered = base.stream()
            .filter(r -> matchSet.contains(r.pmcid))
            .collect(Collectors.toList());

        return filtered.isEmpty() ? base : filtered;
    }

    static void processTopics(String topicsFile, String useField) throws Exception {
        ArrayList<Topic> topics = TopicsReader.readTopics(topicsFile);
        System.out.printf("Processing %d topics  (field=%s  type=%s)%n%n",
                topics.size(), useField, TYPE_FILTER == null ? "auto" : TYPE_FILTER);

        for (Topic topic : topics) {
            String text = "description".equals(useField)
                    ? topic.getDescription() : topic.getSummary();
            String type = TYPE_FILTER != null ? TYPE_FILTER : String.valueOf(topic.getType());

            System.out.printf("Topic %2d [%-11s]: %s%n", topic.getNumber(), topic.getType(), text);

            List<String> stems   = processQuery(text);
            int          fetchK  = (type != null && !"all".equalsIgnoreCase(type)) ? TOP_K * 20 : TOP_K;
            List<Result> results = filterByType(search(stems, fetchK), type, TOP_K);

            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                System.out.printf("  %2d. [%.5f] %s%n", i + 1, r.score, r.path);
                if (SHOW_SNIPPET) System.out.println("       " + getSnippet(r.path, stems));
            }
            System.out.println();
        }
    }
}
