package queryeval;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Pairwise LTR via coordinate ascent on MAP.
// Training samples: double[] = [label, bm25_norm, vsm_norm, sem_cos, rr_bm25, rr_sem]
// Inference: score(feats, w) where feats has NFEAT elements (no label).
public class LTRModel {

    static final int NFEAT = 5;

    static double mapScore(Map<Integer, List<double[]>> data, double[] w) {
        double sum = 0; int n = 0;
        for (List<double[]> pool : data.values()) {
            int numRel = 0;
            for (double[] d : pool) if (d[0] > 0) numRel++;
            if (numRel == 0) continue;
            List<double[]> sorted = new ArrayList<>(pool);
            sorted.sort((a, b) -> Double.compare(dot(b, w), dot(a, w)));
            int seen = 0; double ap = 0;
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i)[0] > 0) { seen++; ap += (double) seen / (i + 1); }
            }
            sum += ap / numRel; n++;
        }
        return n > 0 ? sum / n : 0;
    }

    // dot product using d[1..NFEAT] (skips label at d[0])
    static double dot(double[] d, double[] w) {
        double s = 0;
        for (int i = 0; i < w.length; i++) s += w[i] * d[i + 1];
        return s;
    }

    // inference: feats has no label prefix
    public static double score(double[] feats, double[] w) {
        double s = 0;
        for (int i = 0; i < Math.min(feats.length, w.length); i++) s += w[i] * feats[i];
        return s;
    }

    public static double[] train(Map<Integer, List<double[]>> data, int restarts) {
        Random rnd = new Random(42);
        double[] best = uniform();
        double bestMAP = mapScore(data, best);

        for (int r = 0; r < restarts; r++) {
            double[] w = r == 0 ? uniform() : random(rnd);
            for (int pass = 0; pass < 40; pass++) {
                boolean changed = false;
                for (int i = 0; i < NFEAT; i++) {
                    double orig = w[i], bestVal = orig;
                    double cur = mapScore(data, w);
                    for (double step : new double[]{0.5, 0.2, 0.1, 0.05, -0.05, -0.1, -0.2, -0.5}) {
                        w[i] = Math.max(0, orig + step);
                        double m = mapScore(data, w);
                        if (m > cur + 1e-6) { cur = m; bestVal = w[i]; changed = true; }
                    }
                    w[i] = bestVal;
                }
                if (!changed) break;
            }
            normalize(w);
            double m = mapScore(data, w);
            if (m > bestMAP) { bestMAP = m; best = w.clone(); }
        }
        System.out.printf("LTR trained: MAP=%.4f  weights=%s%n", bestMAP, Arrays.toString(fmt(best)));
        return best;
    }

    static double[] uniform() {
        double[] w = new double[NFEAT];
        Arrays.fill(w, 1.0 / NFEAT);
        return w;
    }

    static double[] random(Random rnd) {
        double[] w = new double[NFEAT];
        for (int i = 0; i < NFEAT; i++) w[i] = rnd.nextDouble();
        normalize(w);
        return w;
    }

    static void normalize(double[] w) {
        double s = 0;
        for (double v : w) s += Math.abs(v);
        if (s > 0) for (int i = 0; i < w.length; i++) w[i] /= s;
    }

    static String[] fmt(double[] w) {
        String[] s = new String[w.length];
        for (int i = 0; i < w.length; i++) s[i] = String.format("%.3f", w[i]);
        return s;
    }

    public static void save(String indexDir, double[] w) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(indexDir + File.separator + "LTRWeights.txt"),
                StandardCharsets.UTF_8))) {
            for (double v : w) pw.println(v);
        }
    }

    public static double[] load(String indexDir) {
        File f = new File(indexDir + File.separator + "LTRWeights.txt");
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            List<Double> vals = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) vals.add(Double.parseDouble(line));
            }
            if (vals.size() != NFEAT) return null;
            double[] w = new double[NFEAT];
            for (int i = 0; i < NFEAT; i++) w[i] = vals.get(i);
            return w;
        } catch (Exception e) { return null; }
    }
}
