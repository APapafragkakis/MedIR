package indexer;

import gr.uoc.csd.hy463.NXMLFileReader;
import mitos.stemmer.Stemmer;
import queryeval.SemanticModel;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Indexer {

    static Map<String, Map<String, Map<String, Integer>>> index = new TreeMap<>();
    static Set<String> stopwords = new HashSet<>();
    static Map<String, String> docPaths = new TreeMap<>();
    static Map<String, Map<String, List<Integer>>> posIndex = new TreeMap<>();
    static Map<String, Integer> docNumericID = new LinkedHashMap<>();
    static Map<String, Long> docFileOffsets = new HashMap<>();
    static int posCounter = 0;
    static final Map<String, Integer> docLengths = new HashMap<>();

    static String BASE_DIR = ".";

    static String resolveBaseDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("Stopwords").resolve("stopwordsEn.txt"))) {
            return cwd.toString();
        }
        try {
            Path src = Paths.get(
                Indexer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
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
        BASE_DIR = resolveBaseDir();

        Stemmer.Initialize();
        loadStopwords(BASE_DIR + File.separator + "Stopwords" + File.separator + "stopwordsEn.txt");
        loadStopwords(BASE_DIR + File.separator + "Stopwords" + File.separator + "stopwordsGr.txt");

        String singleFile = null;
        String folderPath = null;
        boolean writeIndex = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file":     singleFile = args[++i];   writeIndex = false; break;
                case "--folder":   folderPath = args[++i];                       break;
                case "--no-index": writeIndex = false;                           break;
            }
        }

        if (singleFile != null) {
            processFile(new File(singleFile));
            printResults();
            return;
        }

        File folder = new File(folderPath != null
                ? folderPath
                : BASE_DIR + File.separator + "dataset" + File.separator + "clinic");

        System.out.println("Indexing " + folder.getPath() + " ...");
        processFolder(folder);
        System.out.printf("\rProcessed %d documents, %d unique terms.%n",
                docPaths.size(), index.size());

        if (writeIndex) {
            System.out.print("Writing index files... ");
            createDocumentsFile();
            createPostingFile();
            buildSemanticModel();
        }
        printResults();
    }

    static void loadStopwords(String filename) throws IOException {
        File f = new File(filename);
        if (!f.exists()) return;
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            stopwords.add(line.trim().toLowerCase());
        }
        br.close();
    }

    static void processTag(String docID, String tag, String text) {
        if (text == null) return;
        String[] words = text.toLowerCase().replaceAll("[^\\p{L}\\p{Nd} ]+", " ").split("\\s+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            posCounter++;
            if (stopwords.contains(word)) continue;
            if (word.matches("\\d+")) continue;

            index.putIfAbsent(word, new TreeMap<>());
            Map<String, Map<String, Integer>> docMap = index.get(word);

            docMap.putIfAbsent(docID, new TreeMap<>());
            Map<String, Integer> tagMap = docMap.get(docID);

            tagMap.put(tag, tagMap.getOrDefault(tag, 0) + 1);

            posIndex.putIfAbsent(word, new TreeMap<>());
            posIndex.get(word).putIfAbsent(docID, new ArrayList<>());
            posIndex.get(word).get(docID).add(posCounter);
        }
    }

    static void printResults() {
        System.out.println("Indexed " + index.size() + " unique terms across " + docPaths.size() + " documents.");
    }

    static void processFolder(File folder) throws Exception {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                processFolder(file);
            } else if (file.getName().endsWith(".nxml")) {
                processFile(file);
                System.out.printf("\r  %d docs...", docPaths.size());
            }
        }
    }

    static void processFile(File file) throws Exception {
        NXMLFileReader xml = new NXMLFileReader(file);
        String docID = file.getName();
        docPaths.put(docID, file.getAbsolutePath());
        posCounter = 0;

        processTag(docID, "title", xml.getTitle());
        processTag(docID, "abstract", xml.getAbstr());
        processTag(docID, "body", xml.getBody());
        processTag(docID, "journal", xml.getJournal());
        processTag(docID, "publisher", xml.getPublisher());

        for (String author : xml.getAuthors()) {
            processTag(docID, "author", author);
        }
        for (String cat : xml.getCategories()) {
            processTag(docID, "category", cat);
        }
        docLengths.put(docID, posCounter);
    }

    static void createDocumentsFile() throws IOException {
        int N = docPaths.size();
        int id = 0;
        for (String docID : docPaths.keySet()) {
            docNumericID.put(docID, id++);
        }

        Map<String, Double> docNorms = new HashMap<>();
        for (String docID : docPaths.keySet()) {
            docNorms.put(docID, 0.0);
        }

        for (String word : index.keySet()) {
            Map<String, Map<String, Integer>> docMap = index.get(word);
            int df = docMap.size();
            double idf = Math.log((double) N / df);
            for (String docID : docMap.keySet()) {
                int tf = 0;
                for (int count : docMap.get(docID).values()) {
                    tf += count;
                }
                double tfidf = tf * idf;
                docNorms.put(docID, docNorms.get(docID) + tfidf * tfidf);
            }
        }

        for (String docID : docNorms.keySet()) {
            docNorms.put(docID, Math.sqrt(docNorms.get(docID)));
        }

        String indexDir = BASE_DIR + File.separator + "CollectionIndex";
        File dir = new File(indexDir);
        if (!dir.exists()) {
            dir.mkdir();
        }

        RandomAccessFile raf = new RandomAccessFile(indexDir + File.separator + "DocumentsFile.txt", "rw");
        raf.setLength(0);

        for (String docID : docPaths.keySet()) {
            docFileOffsets.put(docID, raf.getFilePointer());
            String line = docNumericID.get(docID) + " | " + docPaths.get(docID) + " | " + docNorms.get(docID) + " | " + docLengths.getOrDefault(docID, 0) + "\n";
            raf.writeBytes(line);
        }

        raf.close();
    }

    static void createPostingFile() throws IOException {
        String indexDir = BASE_DIR + File.separator + "CollectionIndex";
        Map<String, Long> wordPointers = new LinkedHashMap<>();

        RandomAccessFile postingFile = new RandomAccessFile(indexDir + File.separator + "PostingFile.txt", "rw");
        postingFile.setLength(0);

        for (String word : index.keySet()) {
            wordPointers.put(word, postingFile.getFilePointer());
            Map<String, Map<String, Integer>> docMap = index.get(word);
            for (String docID : docMap.keySet()) {
                int numID = docNumericID.get(docID);
                int tf = 0;
                for (int count : docMap.get(docID).values()) {
                    tf += count;
                }
                List<Integer> posList = posIndex.get(word).get(docID);
                String positions = posList.stream().map(String::valueOf).collect(Collectors.joining(","));
                long docOffset = docFileOffsets.get(docID);
                String line = numID + " | " + tf + " | " + positions + " | " + docOffset + "\n";
                postingFile.writeBytes(line);
            }
        }

        postingFile.close();

        BufferedWriter vocabWriter = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(indexDir + File.separator + "VocabularyFile.txt"), StandardCharsets.UTF_8));

        for (String word : index.keySet()) {
            int df = index.get(word).size();
            long pointer = wordPointers.get(word);
            String stem = Stemmer.Stem(word);
            if (stem == null || stem.isEmpty()) stem = word;
            vocabWriter.write(word + " | " + stem + " | " + df + " | " + pointer);
            vocabWriter.newLine();
        }

        vocabWriter.close();
        System.out.println("done.");
    }

    static void buildSemanticModel() throws IOException {
        int N = docPaths.size();
        if (N < 2) return;
        List<String> docOrder = new ArrayList<>(docPaths.keySet());

        Map<String, Map<String, Double>> docStemTf = new LinkedHashMap<>();
        for (String docID : docOrder) docStemTf.put(docID, new HashMap<>());

        for (Map.Entry<String, Map<String, Map<String, Integer>>> we : index.entrySet()) {
            String stem = Stemmer.Stem(we.getKey());
            if (stem == null || stem.isEmpty()) stem = we.getKey();
            for (Map.Entry<String, Map<String, Integer>> de : we.getValue().entrySet()) {
                int tf = 0;
                for (int c : de.getValue().values()) tf += c;
                Map<String, Double> m = docStemTf.get(de.getKey());
                if (m != null) m.merge(stem, (double) tf, Double::sum);
            }
        }

        Map<String, Integer> stemDf = new HashMap<>();
        for (Map<String, Double> m : docStemTf.values())
            for (String stem : m.keySet()) stemDf.merge(stem, 1, Integer::sum);

        List<String> stemList = new ArrayList<>(stemDf.keySet());
        Collections.sort(stemList);
        Map<String, Integer> colOf = new HashMap<>();
        double[] idf = new double[stemList.size()];
        for (int i = 0; i < stemList.size(); i++) {
            colOf.put(stemList.get(i), i);
            idf[i] = Math.log((double) N / stemDf.get(stemList.get(i)));
        }

        int[]      docNums = new int[N];
        String[]   pmcids  = new String[N];
        int[][]    rowCols = new int[N][];
        double[][] rowVals = new double[N][];
        for (int r = 0; r < N; r++) {
            String docID = docOrder.get(r);
            docNums[r] = docNumericID.get(docID);
            pmcids[r]  = docID.replace(".nxml", "");
            Map<String, Double> m = docStemTf.get(docID);
            int[] cols = new int[m.size()];
            double[] vals = new double[m.size()];
            int j = 0;
            for (Map.Entry<String, Double> e : m.entrySet()) {
                int c = colOf.get(e.getKey());
                cols[j] = c;
                vals[j] = e.getValue() * idf[c];
                j++;
            }
            rowCols[r] = cols;
            rowVals[r] = vals;
        }

        String indexDir = BASE_DIR + File.separator + "CollectionIndex";
        int kReq = Math.min(160, N);
        System.out.print("Building LSA embeddings... ");
        SemanticModel.build(indexDir, docNums, pmcids,
                stemList.toArray(new String[0]), idf, rowCols, rowVals, kReq);
        System.out.println("done (" + stemList.size() + " stems).");
    }
}
//
