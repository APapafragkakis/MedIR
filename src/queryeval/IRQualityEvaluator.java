package queryeval;

import gr.uoc.csd.hy463.Topic;
import gr.uoc.csd.hy463.TopicsReader;
import mitos.stemmer.Stemmer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
public class IRQualityEvaluator {

    interface IQueryEngine {
        List<QueryEvaluator.Result> search(List<String> stems, String topicType, int topK) throws Exception;
    }

    static class RealQueryEngine implements IQueryEngine {
        private final String model;
        RealQueryEngine(String model) { this.model = model; }

        @Override
        public List<QueryEvaluator.Result> search(List<String> stems, String topicType, int topK) throws Exception {
            int fetchK = topK * 20;
            List<QueryEvaluator.Result> raw = "bm25".equalsIgnoreCase(model)
                ? QueryEvaluator.searchBM25(stems, fetchK)
                : QueryEvaluator.searchVSM(stems, fetchK);
            return QueryEvaluator.filterByType(raw, topicType, topK);
        }
    }

    static class CachingQueryEngineProxy implements IQueryEngine {
        private final IQueryEngine delegate;
        private final Map<String, List<QueryEvaluator.Result>> cache = new HashMap<>();
        int hits = 0, misses = 0;

        CachingQueryEngineProxy(IQueryEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<QueryEvaluator.Result> search(List<String> stems, String topicType, int topK) throws Exception {
            String key = stems + "|" + topicType + "|" + topK;
            List<QueryEvaluator.Result> cached = cache.get(key);
            if (cached != null) { hits++; return cached; }
            misses++;
            List<QueryEvaluator.Result> result = delegate.search(stems, topicType, topK);
            cache.put(key, result);
            return result;
        }
    }

    static class TopicMetrics {
        int    topicId;
        String topicType;
        int    numRel, numRetrieved, numRelRet;
        double p5, p10, r5, r10, f5, f10, ap, ndcg5, ndcg10, rPrec;
    }

    public static void main(String[] args) throws Exception {
        Stemmer.Initialize();

        String base       = QueryEvaluator.resolveBaseDir();
        String indexDir   = base + File.separator + "CollectionIndex";
        String swEn       = base + File.separator + "Stopwords" + File.separator + "stopwordsEn.txt";
        String swGr       = base + File.separator + "Stopwords" + File.separator + "stopwordsGr.txt";
        String datasetDir = base + File.separator + "dataset" + File.separator + "clinic" + File.separator + "MiniCollection";
        String topicsFile = base + File.separator + "topics.xml";
        String docDir     = base + File.separator + "doc";
        int    topK       = 10;
        String useField   = "summary";
        String model      = "both";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index":   indexDir   = args[++i]; break;
                case "--topics":  topicsFile = args[++i]; break;
                case "--dataset": datasetDir = args[++i]; break;
                case "--topk":    topK       = Integer.parseInt(args[++i]); break;
                case "--output":  docDir     = args[++i]; break;
                case "--field":   useField   = args[++i]; break;
                case "--model":   model      = args[++i]; break;
            }
        }

        String evalOut    = docDir + File.separator + "eval_results.txt";
        String resultsOut = docDir + File.separator + "results.txt";
        String qrelsOut   = docDir + File.separator + "qrels.txt";

        // initialize QueryEvaluator
        QueryEvaluator.INDEX_DIR    = indexDir;
        QueryEvaluator.STOPWORDS_EN = swEn;
        QueryEvaluator.STOPWORDS_GR = swGr;
        QueryEvaluator.loadStopwords(swEn);
        QueryEvaluator.loadStopwords(swGr);
        QueryEvaluator.loadVocabulary();
        QueryEvaluator.countDocuments();
        System.out.println("Index: " + QueryEvaluator.vocabulary.size()
                + " terms | " + QueryEvaluator.totalDocs + " docs");

        ArrayList<Topic> allTopics = TopicsReader.readTopics(topicsFile);

        // Build qrels from the MiniCollection directory structure
        Map<Integer, Map<String, Integer>> qrels = buildQrelsFromDir(datasetDir);
        System.out.println("Ground truth for " + qrels.size() + " topics");

        // Write qrels.txt 
        writeQrelsTrec(qrelsOut, qrels);

        // Only evaluate topics that have ground truth
        List<Topic> evalTopics = new ArrayList<>();
        for (Topic t : allTopics) {
            if (qrels.containsKey(t.getNumber())) evalTopics.add(t);
        }
        System.out.println("Evaluating " + evalTopics.size() + " topics\n");

        List<String> modelsToRun = "both".equalsIgnoreCase(model)
            ? Arrays.asList("vsm", "bm25")
            : Collections.singletonList(model.toLowerCase());

        Map<String, List<TopicMetrics>> allModelMetrics = new LinkedHashMap<>();
        StringBuilder trecResults = new StringBuilder();

        for (String m : modelsToRun) {
            CachingQueryEngineProxy engine = new CachingQueryEngineProxy(new RealQueryEngine(m));
            List<TopicMetrics> allMetrics  = new ArrayList<>();

            if (modelsToRun.size() > 1) System.out.println("--- " + m.toUpperCase() + " ---");

            for (Topic topic : evalTopics) {
                int    id   = topic.getNumber();
                String type = String.valueOf(topic.getType());
                String text = "description".equals(useField) ? topic.getDescription() : topic.getSummary();

                List<String>                stems   = QueryEvaluator.processQuery(text);
                List<QueryEvaluator.Result> results = engine.search(stems, type, topK);
                Map<String, Integer>        tQrels  = qrels.get(id);

                for (int r = 0; r < results.size(); r++) {
                    trecResults.append(id).append(" Q0 ")
                            .append(results.get(r).pmcid).append(" ")
                            .append(r + 1).append(" ")
                            .append(String.format("%.6f", results.get(r).score))
                            .append(" ").append(m.toUpperCase()).append("\n");
                }

                TopicMetrics tm = computeMetrics(id, type, results, tQrels, topK);
                allMetrics.add(tm);
                printTopicLine(tm);
            }

            System.out.printf("%nProxy cache: %d hits / %d misses%n%n", engine.hits, engine.misses);
            allModelMetrics.put(m, allMetrics);
        }

        if (modelsToRun.size() > 1) printComparison(allModelMetrics);

        writeFile(resultsOut, trecResults.toString());
        writeEvalTSV(evalOut, allModelMetrics.values().iterator().next());
        System.out.println("results.txt      -> " + resultsOut);
        System.out.println("qrels.txt        -> " + qrelsOut);
        System.out.println("eval_results.txt -> " + evalOut);
    }

    static Map<Integer, Map<String, Integer>> buildQrelsFromDir(String datasetDir) {
        Map<Integer, Map<String, Integer>> qrels = new TreeMap<>();
        File base = new File(datasetDir);
        if (!base.isDirectory()) return qrels;

        for (File typeDir : safeList(base)) {
            if (!typeDir.isDirectory()) continue;
            for (File topicDir : safeList(typeDir)) {
                if (!topicDir.isDirectory() || !topicDir.getName().startsWith("Topic_")) continue;
                int topicId;
                try { topicId = Integer.parseInt(topicDir.getName().substring(6)); }
                catch (NumberFormatException e) { continue; }

                qrels.putIfAbsent(topicId, new TreeMap<>());
                for (File relDir : safeList(topicDir)) {
                    if (!relDir.isDirectory()) continue;
                    int rel;
                    try { rel = Integer.parseInt(relDir.getName()); }
                    catch (NumberFormatException e) { continue; }

                    for (File doc : safeList(relDir)) {
                        if (doc.getName().endsWith(".nxml"))
                            qrels.get(topicId).put(doc.getName().replace(".nxml", ""), rel);
                    }
                }
            }
        }
        return qrels;
    }

    static File[] safeList(File dir) {
        File[] files = dir.listFiles();
        return files != null ? files : new File[0];
    }

    static TopicMetrics computeMetrics(int topicId, String topicType,
                                        List<QueryEvaluator.Result> results,
                                        Map<String, Integer> qrels,
                                        int topK) {
        TopicMetrics m = new TopicMetrics();
        m.topicId      = topicId;
        m.topicType    = topicType;
        m.numRetrieved = results.size();

        int numRel = 0;
        for (int v : qrels.values()) if (v >= 1) numRel++;
        m.numRel = numRel;

        int    relAt5 = 0, relAt10 = 0, numRelRet = 0, relSeen = 0;
        double sumAP = 0, dcg5 = 0, dcg10 = 0;

        for (int i = 0; i < results.size(); i++) {
            int    rank  = i + 1;
            String pmcid = results.get(i).pmcid;
            int    rel   = qrels.getOrDefault(pmcid, 0);

            // Graded DCG: gain = rel * log2 / log(rank+1)
            double gain = rel * Math.log(2) / Math.log(rank + 1);
            if (rank <= 5)  dcg5  += gain;
            if (rank <= 10) dcg10 += gain;

            if (rel >= 1) {
                numRelRet++;
                relSeen++;
                if (rank <= 5)  relAt5++;
                if (rank <= 10) relAt10++;
                sumAP += (double) relSeen / rank;
            }
        }

        m.numRelRet = numRelRet;

        // P@K divides by K (non-retrieved docs count as non-relevant)
        m.p5  = relAt5  / 5.0;
        m.p10 = relAt10 / 10.0;
        m.r5  = numRel > 0 ? relAt5  / (double) numRel : 0;
        m.r10 = numRel > 0 ? relAt10 / (double) numRel : 0;
        m.f5  = safeHarmonic(m.p5,  m.r5);
        m.f10 = safeHarmonic(m.p10, m.r10);
        m.ap  = numRel > 0 ? sumAP / numRel : 0;

        double idcg5  = computeIDCG(qrels, 5);
        double idcg10 = computeIDCG(qrels, 10);
        m.ndcg5  = idcg5  > 0 ? dcg5  / idcg5  : 0;
        m.ndcg10 = idcg10 > 0 ? dcg10 / idcg10 : 0;

        // R-Prec: precision at rank R (R = numRel)
        if (numRel > 0) {
            int bound = Math.min(numRel, results.size());
            int relAtR = 0;
            for (int i = 0; i < bound; i++)
                if (qrels.getOrDefault(results.get(i).pmcid, 0) >= 1) relAtR++;
            m.rPrec = (double) relAtR / numRel;
        }

        return m;
    }

    static double safeHarmonic(double p, double r) {
        return (p + r > 0) ? 2 * p * r / (p + r) : 0;
    }

    // Ideal DCG: sort grades descending, sum top-k gains
    static double computeIDCG(Map<String, Integer> qrels, int k) {
        List<Integer> grades = new ArrayList<>(qrels.values());
        grades.sort(Collections.reverseOrder());
        double idcg = 0;
        for (int i = 0; i < Math.min(k, grades.size()); i++)
            idcg += grades.get(i) * Math.log(2) / Math.log(i + 2);
        return idcg;
    }

    static void printTopicLine(TopicMetrics m) {
        System.out.printf(
            "Topic %2d [%-9s] Rel=%2d Ret=%2d  P@5=%.3f P@10=%.3f  " +
            "R@5=%.3f R@10=%.3f  AP=%.3f  NDCG@10=%.3f  R-Prec=%.3f%n",
            m.topicId, m.topicType, m.numRel, m.numRelRet,
            m.p5, m.p10, m.r5, m.r10, m.ap, m.ndcg10, m.rPrec);
    }

    static void writeEvalTSV(String path, List<TopicMetrics> metrics) throws IOException {
        ensureParentDir(path);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {

            pw.println("TopicID\tType\tNumRel\tNumRetrieved\tNumRelRet\t" +
                       "P@5\tP@10\tR@5\tR@10\tF1@5\tF1@10\tAP\tNDCG@5\tNDCG@10\tR-Prec");

            double sp5=0,sp10=0,sr5=0,sr10=0,sf5=0,sf10=0,sap=0,sn5=0,sn10=0,srp=0;
            for (TopicMetrics m : metrics) {
                pw.printf("%d\t%s\t%d\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                        m.topicId, m.topicType, m.numRel, m.numRetrieved, m.numRelRet,
                        m.p5, m.p10, m.r5, m.r10, m.f5, m.f10, m.ap, m.ndcg5, m.ndcg10, m.rPrec);
                sp5+=m.p5; sp10+=m.p10; sr5+=m.r5; sr10+=m.r10;
                sf5+=m.f5; sf10+=m.f10; sap+=m.ap;
                sn5+=m.ndcg5; sn10+=m.ndcg10; srp+=m.rPrec;
            }

            int n = metrics.size();
            if (n > 0) {
                System.out.printf("%nAggregate (mean over %d topics):%n", n);
                System.out.printf("  MAP=%.4f  P@10=%.4f  NDCG@10=%.4f  R-Prec=%.4f%n",
                        sap/n, sp10/n, sn10/n, srp/n);
                pw.printf("MAP/Avg\t-\t-\t-\t-\t" +
                          "%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                        sp5/n, sp10/n, sr5/n, sr10/n, sf5/n, sf10/n,
                        sap/n, sn5/n, sn10/n, srp/n);
            }
        }
    }

    static void printComparison(Map<String, List<TopicMetrics>> modelMetrics) {
        System.out.println("\n==========================================");
        System.out.println("  Model Comparison");
        System.out.println("==========================================");
        System.out.printf("  %-10s  %8s  %8s  %8s  %8s%n", "Model", "MAP", "P@10", "NDCG@10", "R-Prec");
        System.out.println("  ----------------------------------------");
        for (Map.Entry<String, List<TopicMetrics>> e : modelMetrics.entrySet()) {
            List<TopicMetrics> metrics = e.getValue();
            int n = metrics.size();
            double map = metrics.stream().mapToDouble(m -> m.ap).sum() / n;
            double p10 = metrics.stream().mapToDouble(m -> m.p10).sum() / n;
            double ndcg = metrics.stream().mapToDouble(m -> m.ndcg10).sum() / n;
            double rp  = metrics.stream().mapToDouble(m -> m.rPrec).sum() / n;
            System.out.printf("  %-10s  %8.4f  %8.4f  %8.4f  %8.4f%n",
                e.getKey().toUpperCase(), map, p10, ndcg, rp);
        }
        System.out.println("==========================================\n");
    }

    static void writeQrelsTrec(String path, Map<Integer, Map<String, Integer>> qrels) throws IOException {
        ensureParentDir(path);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            for (Map.Entry<Integer, Map<String, Integer>> e : qrels.entrySet()) {
                int topicId = e.getKey();
                for (Map.Entry<String, Integer> d : e.getValue().entrySet())
                    pw.printf("%d\t0\t%s\t%d%n", topicId, d.getKey(), d.getValue());
            }
        }
    }

    static void ensureParentDir(String filePath) {
        File parent = new File(filePath).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    static void writeFile(String path, String content) throws IOException {
        ensureParentDir(path);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
