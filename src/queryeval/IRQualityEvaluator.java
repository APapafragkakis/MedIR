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
            List<QueryEvaluator.Result> raw = QueryEvaluator.searchByModel(stems, fetchK, model);
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
        double[] prCurve; // 11-point interpolated P/R (recall 0.0 .. 1.0)
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

        Map<Integer, Map<String, Integer>> qrels = buildQrelsFromDir(datasetDir);
        System.out.println("Ground truth for " + qrels.size() + " topics");

        writeQrelsTrec(qrelsOut, qrels);

        List<Topic> evalTopics = new ArrayList<>();
        for (Topic t : allTopics) {
            if (qrels.containsKey(t.getNumber())) evalTopics.add(t);
        }
        System.out.println("Evaluating " + evalTopics.size() + " topics\n");

        boolean runAll = "all".equalsIgnoreCase(model);
        List<String> modelsToRun =
              "both".equalsIgnoreCase(model) ? Arrays.asList("vsm", "bm25")
            : runAll ? Arrays.asList("vsm", "bm25", "semantic", "hybrid", "ltr")
            : Collections.singletonList(model.toLowerCase());

        // Train LTR when running all models — builds feature pool from qrels + all three rankers
        if (runAll) {
            System.out.println("Training LTR model (coordinate ascent)...");
            Map<Integer, List<double[]>> ltrData = buildLTRData(evalTopics, qrels, useField);
            double[] weights = LTRModel.train(ltrData, 5);
            LTRModel.save(indexDir, weights);
            QueryEvaluator.ltrWeights = weights;
            QueryEvaluator.ltrTried = true;
        }

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
        String prCurveOut = docDir + File.separator + "prcurve.json";
        writePRCurveJson(prCurveOut, allModelMetrics);
        System.out.println("results.txt      -> " + resultsOut);
        System.out.println("qrels.txt        -> " + qrelsOut);
        System.out.println("eval_results.txt -> " + evalOut);
        System.out.println("prcurve.json     -> " + prCurveOut);
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

        if (numRel > 0) {
            int bound = Math.min(numRel, results.size());
            int relAtR = 0;
            for (int i = 0; i < bound; i++)
                if (qrels.getOrDefault(results.get(i).pmcid, 0) >= 1) relAtR++;
            m.rPrec = (double) relAtR / numRel;
        }

        // 11-point interpolated P/R
        int[] cumRel = new int[results.size()];
        int cr = 0;
        for (int i = 0; i < results.size(); i++) {
            if (qrels.getOrDefault(results.get(i).pmcid, 0) >= 1) cr++;
            cumRel[i] = cr;
        }
        m.prCurve = new double[11];
        if (numRel > 0) {
            for (int r = 0; r <= 10; r++) {
                double thresh = r / 10.0;
                double maxP = 0;
                for (int i = 0; i < results.size(); i++) {
                    double rec = (double) cumRel[i] / numRel;
                    if (rec >= thresh - 1e-9)
                        maxP = Math.max(maxP, (double) cumRel[i] / (i + 1));
                }
                m.prCurve[r] = maxP;
            }
        }

        return m;
    }

    static double safeHarmonic(double p, double r) {
        return (p + r > 0) ? 2 * p * r / (p + r) : 0;
    }

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

        List<String> names = new ArrayList<>(modelMetrics.keySet());
        Map<String, double[]> apVecs = new LinkedHashMap<>();

        for (String name : names) {
            List<TopicMetrics> metrics = modelMetrics.get(name);
            int n = metrics.size();
            double map = metrics.stream().mapToDouble(m -> m.ap).sum() / n;
            double p10 = metrics.stream().mapToDouble(m -> m.p10).sum() / n;
            double ndcg = metrics.stream().mapToDouble(m -> m.ndcg10).sum() / n;
            double rp  = metrics.stream().mapToDouble(m -> m.rPrec).sum() / n;
            System.out.printf("  %-10s  %8.4f  %8.4f  %8.4f  %8.4f%n",
                    name.toUpperCase(), map, p10, ndcg, rp);
            double[] ap = metrics.stream().mapToDouble(m -> m.ap).toArray();
            apVecs.put(name, ap);
        }

        System.out.println("\n  Statistical significance (Wilcoxon, per-topic AP, two-tailed):");
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                double p = wilcoxon(apVecs.get(names.get(i)), apVecs.get(names.get(j)));
                String sig = p < 0.001 ? "***" : p < 0.01 ? "**" : p < 0.05 ? "*" : "ns";
                System.out.printf("  %-8s vs %-8s  p = %.3f  %s%n",
                        names.get(i).toUpperCase(), names.get(j).toUpperCase(), p, sig);
            }
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

    // Wilcoxon signed-rank test (two-tailed) on per-topic AP arrays. Returns p-value.
    static double wilcoxon(double[] a, double[] b) {
        int n = a.length;
        List<Double> diffs = new ArrayList<>();
        for (int i = 0; i < n; i++) { double d = a[i] - b[i]; if (Math.abs(d) > 1e-10) diffs.add(d); }
        int m = diffs.size();
        if (m < 2) return 1.0;

        Integer[] idx = new Integer[m];
        for (int i = 0; i < m; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(Math.abs(diffs.get(x)), Math.abs(diffs.get(y))));

        double[] rank = new double[m];
        int i = 0;
        while (i < m) {
            int j = i;
            while (j < m && Math.abs(diffs.get(idx[j])) == Math.abs(diffs.get(idx[i]))) j++;
            double r = (i + j + 1) / 2.0;
            for (int k = i; k < j; k++) rank[idx[k]] = r;
            i = j;
        }

        double wPlus = 0, wMinus = 0;
        for (int k = 0; k < m; k++) {
            if (diffs.get(k) > 0) wPlus += rank[k]; else wMinus += rank[k];
        }
        double W = Math.min(wPlus, wMinus);
        double mu    = m * (m + 1) / 4.0;
        double sigma = Math.sqrt(m * (m + 1) * (2 * m + 1) / 24.0);
        if (sigma == 0) return 1.0;
        double z = Math.abs(W - mu) / sigma;
        return 2.0 * (1.0 - normalCDF(z));
    }

    static double normalCDF(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double p = 1.0 - (1.0 / Math.sqrt(2 * Math.PI)) * Math.exp(-0.5 * z * z)
                * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        return z >= 0 ? p : 1.0 - p;
    }

    // Build feature pool for LTR training.
    // Each sample: [label, bm25_norm, vsm_norm, sem_cos, rr_bm25, rr_sem]
    static Map<Integer, List<double[]>> buildLTRData(
            List<Topic> topics, Map<Integer, Map<String, Integer>> qrels,
            String useField) throws Exception {

        Map<Integer, List<double[]>> data = new LinkedHashMap<>();
        int pool = 50;

        for (Topic topic : topics) {
            int id = topic.getNumber();
            String text = "description".equals(useField)
                    ? topic.getDescription() : topic.getSummary();
            List<String> stems = QueryEvaluator.processQuery(text);
            if (stems.isEmpty()) continue;

            List<QueryEvaluator.Result> bm = QueryEvaluator.searchBM25(stems, pool);
            List<QueryEvaluator.Result> vs = QueryEvaluator.searchVSM(stems, pool);
            List<QueryEvaluator.Result> sm = QueryEvaluator.searchSemantic(stems, pool);

            double maxBm = bm.isEmpty() ? 1 : bm.get(0).score;
            double maxVs = vs.isEmpty() ? 1 : vs.get(0).score;

            // [label, bm25_n, vsm_n, sem, rr_bm, rr_sm]
            Map<String, double[]> feats = new LinkedHashMap<>();
            for (int j = 0; j < bm.size(); j++) {
                QueryEvaluator.Result r = bm.get(j);
                double[] f = feats.computeIfAbsent(r.pmcid, k -> new double[LTRModel.NFEAT + 1]);
                f[1] = maxBm > 0 ? r.score / maxBm : 0;
                f[4] = 1.0 / (j + 1);
            }
            for (int j = 0; j < vs.size(); j++) {
                QueryEvaluator.Result r = vs.get(j);
                double[] f = feats.computeIfAbsent(r.pmcid, k -> new double[LTRModel.NFEAT + 1]);
                f[2] = maxVs > 0 ? r.score / maxVs : 0;
            }
            for (int j = 0; j < sm.size(); j++) {
                QueryEvaluator.Result r = sm.get(j);
                double[] f = feats.computeIfAbsent(r.pmcid, k -> new double[LTRModel.NFEAT + 1]);
                f[3] = r.score;
                f[5] = 1.0 / (j + 1);
            }

            Map<String, Integer> tq = qrels.getOrDefault(id, Collections.emptyMap());
            for (Map.Entry<String, double[]> e : feats.entrySet())
                e.getValue()[0] = tq.getOrDefault(e.getKey(), 0) >= 1 ? 1.0 : 0.0;

            data.put(id, new ArrayList<>(feats.values()));
        }
        return data;
    }

    static void writePRCurveJson(String path, Map<String, List<TopicMetrics>> modelMetrics) throws IOException {
        ensureParentDir(path);
        StringBuilder sb = new StringBuilder("{\"recall_levels\":[");
        for (int r = 0; r <= 10; r++) { if (r > 0) sb.append(","); sb.append(String.format("%.1f", r / 10.0)); }
        sb.append("],\"curves\":{");
        boolean firstModel = true;
        for (Map.Entry<String, List<TopicMetrics>> e : modelMetrics.entrySet()) {
            if (!firstModel) sb.append(",");
            firstModel = false;
            List<TopicMetrics> mets = e.getValue();
            double[] avg = new double[11];
            int cnt = 0;
            for (TopicMetrics tm : mets) {
                if (tm.prCurve == null) continue;
                for (int r = 0; r <= 10; r++) avg[r] += tm.prCurve[r];
                cnt++;
            }
            sb.append("\"").append(e.getKey()).append("\":[");
            for (int r = 0; r <= 10; r++) {
                if (r > 0) sb.append(",");
                sb.append(String.format("%.4f", cnt > 0 ? avg[r] / cnt : 0));
            }
            sb.append("]");
        }
        sb.append("}}");
        writeFile(path, sb.toString());
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
