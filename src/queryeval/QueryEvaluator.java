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
    static int totalDocs = 0;

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
        @Override
        public int compareTo(Result o) { return Double.compare(o.score, this.score); }
    }

    static String resolveBaseDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("Stopwords").resolve("stopwordsEn.txt"))) {
            return cwd.toString();
        }
        try {
            Path src = Paths.get(
                QueryEvaluator.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath();
            for (Path p = src; p != null; p = p.getParent()) {
                if (Files.exists(p.resolve("Stopwords").resolve("stopwordsEn.txt"))) {
                    return p.toString();
                }
                Path candidate = p.resolve("Retrival_Information_project-main").resolve("Phase_A");
                if (Files.exists(candidate.resolve("Stopwords").resolve("stopwordsEn.txt"))) {
                    return candidate.toString();
                }
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
            }
        }

        loadStopwords(STOPWORDS_EN);
        loadStopwords(STOPWORDS_GR);
        loadVocabulary();
        countDocuments();

        System.out.println("Index loaded: " + vocabulary.size() + " terms | " + totalDocs + " documents");

        if ("topics".equals(mode)) {
            processTopics(topicsFile, useField);
        } else {
            interactiveMode();
        }
    }

    static void loadStopwords(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null)
                stopwords.add(line.trim().toLowerCase());
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
        try (RandomAccessFile raf = new RandomAccessFile(
                INDEX_DIR + File.separator + "DocumentsFile.txt", "r")) {
            while (raf.readLine() != null) totalDocs++;
        }
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
            if (d <= 2) {
                ranked.computeIfAbsent(d, k -> new ArrayList<>()).add(vocabStem);
            }
        }
        for (List<String> bucket : ranked.values()) {
            for (String s : bucket) {
                suggestions.add(s);
                if (suggestions.size() >= 3) return suggestions;
            }
        }
        return suggestions;
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
                    List<String> suggestions = suggestOOV(queryStem);
                    if (!suggestions.isEmpty()) {
                        System.out.println("  [OOV] '" + queryStem + "' not in index -> trying " + suggestions);
                        List<String> merged = new ArrayList<>();
                        for (String s : suggestions) merged.addAll(stemToWords.getOrDefault(s, Collections.emptyList()));
                        words = merged;
                    } else {
                        System.out.println("  [OOV] '" + queryStem + "' not in index, no suggestion found.");
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
                        int    docNum = Integer.parseInt(p[0].trim());
                        int    tf     = Integer.parseInt(p[1].trim());
                        long   dOff   = Long.parseLong(p[3].trim());

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
                    String ws = Stemmer.Stem(cleaned);
                    if (queryStems.contains(ws)) {
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

    static void interactiveMode() throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String sep = "─".repeat(56);

        System.out.println("\nMedIR  —  'exit' to quit, prefix ':' for snippet\n");

        while (true) {
            System.out.print("> ");
            String input = br.readLine();
            if (input == null || input.equalsIgnoreCase("exit")) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            boolean showSnip = SHOW_SNIPPET || input.startsWith(":");
            if (input.startsWith(":")) input = input.substring(1).trim();

            long         t0    = System.currentTimeMillis();
            List<String> stems = processQuery(input);

            if (stems.isEmpty()) {
                System.out.println("No indexable terms.\n");
                continue;
            }

            int          fetchK  = (TYPE_FILTER != null && !"all".equalsIgnoreCase(TYPE_FILTER)) ? TOP_K * 20 : TOP_K;
            List<Result> raw     = searchVSM(stems, fetchK);
            List<Result> results = filterByType(raw, TYPE_FILTER, TOP_K);
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

    static List<Result> filterByType(List<Result> results, String type, int topK) {
        if (type == null || "all".equalsIgnoreCase(type)) {
            return results.subList(0, Math.min(topK, results.size()));
        }
        String token = File.separator + type.toLowerCase() + File.separator;
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

    static void processTopics(String topicsFile, String useField) throws Exception {
        ArrayList<Topic> topics = TopicsReader.readTopics(topicsFile);
        System.out.printf("Processing %d topics  (field = %s, type filter = %s)%n%n",
                topics.size(), useField, TYPE_FILTER == null ? "auto-by-topic" : TYPE_FILTER);

        for (Topic topic : topics) {
            String text = "description".equals(useField)
                    ? topic.getDescription()
                    : topic.getSummary();

            String type = TYPE_FILTER != null ? TYPE_FILTER : String.valueOf(topic.getType());

            System.out.printf("Topic %2d [%-11s]: %s%n",
                    topic.getNumber(), topic.getType(), text);

            List<String> stems  = processQuery(text);
            int          fetchK = (type != null && !"all".equalsIgnoreCase(type)) ? TOP_K * 20 : TOP_K;
            List<Result> raw    = searchVSM(stems, fetchK);
            List<Result> results = filterByType(raw, type, TOP_K);

            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                System.out.printf("  %2d. [%.5f] %s%n", i + 1, r.score, r.path);
                if (SHOW_SNIPPET)
                    System.out.println("       Snippet: " + getSnippet(r.path, stems));
            }
            System.out.println();
        }
    }
}
