package queryeval;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// LSA over the collection: truncated SVD of the doc-stem tf-idf matrix, done
// by hand with power iteration + deflation. The dense doc vectors feed the
// semantic / hybrid search and the cluster map.
public class SemanticModel {

    final int               k;
    final int[]             docNumByRow;
    final String[]          pmcidByRow;
    final double[][]        docEmb;     // doc vectors A*V, not normalised
    final double[]          docNorm;
    final String[]          stemByCol;
    final double[]          idf;
    final double[][]        termProj;   // stem -> latent space (rows of V)
    final Map<String,Integer> colOfStem;

    SemanticModel(int k, int[] docNumByRow, String[] pmcidByRow, double[][] docEmb,
                  String[] stemByCol, double[] idf, double[][] termProj) {
        this.k = k;
        this.docNumByRow = docNumByRow;
        this.pmcidByRow  = pmcidByRow;
        this.docEmb      = docEmb;
        this.stemByCol   = stemByCol;
        this.idf         = idf;
        this.termProj    = termProj;
        this.docNorm     = new double[docEmb.length];
        for (int i = 0; i < docEmb.length; i++) docNorm[i] = norm(docEmb[i]);
        this.colOfStem   = new HashMap<>();
        for (int c = 0; c < stemByCol.length; c++) colOfStem.put(stemByCol[c], c);
    }

    public double[] projectQuery(List<String> stems) {
        double[] q = new double[k];
        Map<String,Integer> tf = new HashMap<>();
        for (String s : stems) tf.merge(s, 1, Integer::sum);
        for (Map.Entry<String,Integer> e : tf.entrySet()) {
            Integer c = colOfStem.get(e.getKey());
            if (c == null) continue;
            double w = e.getValue() * idf[c];
            double[] proj = termProj[c];
            for (int j = 0; j < k; j++) q[j] += w * proj[j];
        }
        return q;
    }

    public List<int[]> rankDocNums(List<String> stems, int topK) {
        double[] q  = projectQuery(stems);
        double   qn = norm(q);
        List<double[]> scored = new ArrayList<>();
        if (qn > 0) {
            for (int i = 0; i < docEmb.length; i++) {
                if (docNorm[i] == 0) continue;
                double cos = dot(q, docEmb[i]) / (qn * docNorm[i]);
                if (cos > 0) scored.add(new double[]{docNumByRow[i], cos});
            }
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++)
            out.add(new int[]{(int) scored.get(i)[0]});
        return out;
    }

    public List<double[]> rankScored(List<String> stems, int topK) {
        double[] q  = projectQuery(stems);
        double   qn = norm(q);
        List<double[]> scored = new ArrayList<>();
        if (qn > 0)
            for (int i = 0; i < docEmb.length; i++) {
                if (docNorm[i] == 0) continue;
                double cos = dot(q, docEmb[i]) / (qn * docNorm[i]);
                if (cos > 0) scored.add(new double[]{docNumByRow[i], cos});
            }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    // first two latent dims per doc, scaled to [-1,1] for the scatter plot
    public double[][] map2D() {
        int n = docEmb.length;
        double[][] pts = new double[n][2];
        double mx = 1e-9, my = 1e-9;
        for (int i = 0; i < n; i++) {
            pts[i][0] = k > 0 ? docEmb[i][0] : 0;
            pts[i][1] = k > 1 ? docEmb[i][1] : 0;
            mx = Math.max(mx, Math.abs(pts[i][0]));
            my = Math.max(my, Math.abs(pts[i][1]));
        }
        for (int i = 0; i < n; i++) { pts[i][0] /= mx; pts[i][1] /= my; }
        return pts;
    }

    // spherical k-means (cosine) over the doc embeddings
    public int[] kmeans(int numClusters, int iters) {
        int n = docEmb.length;
        numClusters = Math.max(1, Math.min(numClusters, n));
        double[][] unit = new double[n][k];
        for (int i = 0; i < n; i++) {
            double nn = docNorm[i] == 0 ? 1 : docNorm[i];
            for (int j = 0; j < k; j++) unit[i][j] = docEmb[i][j] / nn;
        }
        double[][] cent = new double[numClusters][k];
        for (int c = 0; c < numClusters; c++) cent[c] = unit[c * n / numClusters].clone();
        int[] assign = new int[n];
        for (int it = 0; it < iters; it++) {
            for (int i = 0; i < n; i++) {
                int    best = 0;
                double bs   = -2;
                for (int c = 0; c < numClusters; c++) {
                    double s = dot(unit[i], cent[c]);
                    if (s > bs) { bs = s; best = c; }
                }
                assign[i] = best;
            }
            double[][] nc = new double[numClusters][k];
            int[] cnt = new int[numClusters];
            for (int i = 0; i < n; i++) {
                cnt[assign[i]]++;
                for (int j = 0; j < k; j++) nc[assign[i]][j] += unit[i][j];
            }
            for (int c = 0; c < numClusters; c++) {
                double nn = norm(nc[c]);
                if (nn > 0) for (int j = 0; j < k; j++) nc[c][j] /= nn;
                else nc[c] = unit[(c * n / numClusters) % n].clone();
            }
            cent = nc;
        }
        return assign;
    }

    public List<List<String>> clusterKeywords(int[] assign, int numClusters, int perCluster) {
        double[][] cent = new double[numClusters][k];
        int[] cnt = new int[numClusters];
        for (int i = 0; i < docEmb.length; i++) {
            double nn = docNorm[i] == 0 ? 1 : docNorm[i];
            cnt[assign[i]]++;
            for (int j = 0; j < k; j++) cent[assign[i]][j] += docEmb[i][j] / nn;
        }
        List<List<String>> out = new ArrayList<>();
        for (int c = 0; c < numClusters; c++) {
            final double[] cc = cent[c];
            double cn = norm(cc);
            List<double[]> best = new ArrayList<>();
            for (int col = 0; col < stemByCol.length; col++) {
                double tn = norm(termProj[col]);
                if (tn == 0 || cn == 0) continue;
                double sim = dot(cc, termProj[col]) / (cn * tn);
                best.add(new double[]{col, sim});
            }
            best.sort((a, b) -> Double.compare(b[1], a[1]));
            List<String> ks = new ArrayList<>();
            for (int i = 0; i < Math.min(perCluster, best.size()); i++)
                ks.add(stemByCol[(int) best.get(i)[0]]);
            out.add(ks);
        }
        return out;
    }

    // rowCols[i] / rowVals[i] are the sparse non-zeros of doc row i
    public static SemanticModel build(String indexDir, int[] docNums, String[] pmcids,
                               String[] stems, double[] idf,
                               int[][] rowCols, double[][] rowVals, int kRequested) throws IOException {
        int d = docNums.length;
        int V = stems.length;
        int k = Math.min(kRequested, Math.min(d, V));

        double[][] U     = new double[k][d];   // left singular vectors
        double[][] Vt    = new double[k][V];   // right singular vectors
        double[]   sigma = new double[k];

        Random rnd = new Random(42);
        int kept = 0;
        for (int comp = 0; comp < k; comp++) {
            double[] u = new double[d];
            for (int i = 0; i < d; i++) u[i] = rnd.nextDouble() - 0.5;
            normalize(u);
            double[] v = new double[V];
            double prev = -1;
            for (int iter = 0; iter < 120; iter++) {
                // v = A^T u  (deflated)
                Arrays.fill(v, 0);
                for (int i = 0; i < d; i++) {
                    double ui = u[i];
                    if (ui == 0) continue;
                    int[] cols = rowCols[i]; double[] vals = rowVals[i];
                    for (int t = 0; t < cols.length; t++) v[cols[t]] += ui * vals[t];
                }
                for (int l = 0; l < comp; l++) {
                    double du = dot(U[l], u);
                    double sl = sigma[l];
                    for (int t = 0; t < V; t++) v[t] -= sl * Vt[l][t] * du;
                }
                normalize(v);
                // u = A v  (deflated)
                Arrays.fill(u, 0);
                for (int i = 0; i < d; i++) {
                    int[] cols = rowCols[i]; double[] vals = rowVals[i];
                    double acc = 0;
                    for (int t = 0; t < cols.length; t++) acc += vals[t] * v[cols[t]];
                    u[i] = acc;
                }
                for (int l = 0; l < comp; l++) {
                    double dv = dot(Vt[l], v);
                    double sl = sigma[l];
                    for (int i = 0; i < d; i++) u[i] -= sl * U[l][i] * dv;
                }
                double s = norm(u);
                if (s < 1e-9) break;
                for (int i = 0; i < d; i++) u[i] /= s;
                if (prev > 0 && Math.abs(s - prev) / s < 1e-6) { sigma[comp] = s; break; }
                prev = s;
                sigma[comp] = s;
            }
            if (sigma[comp] < 1e-6) break;
            U[comp]  = u;
            Vt[comp] = v;
            kept++;
        }
        k = kept;

        // Doc embeddings D = A*V  (rows). termProj W = V rows (stem -> latent).
        double[][] docEmb = new double[d][k];
        for (int i = 0; i < d; i++)
            for (int j = 0; j < k; j++)
                docEmb[i][j] = U[j][i] * sigma[j];        // (A V)[i,j] = U[i,j]*sigma[j]
        double[][] termProj = new double[V][k];
        for (int t = 0; t < V; t++)
            for (int j = 0; j < k; j++)
                termProj[t][j] = Vt[j][t];

        SemanticModel m = new SemanticModel(k, docNums, pmcids, docEmb, stems, idf, termProj);
        m.save(indexDir);
        return m;
    }

    void save(String indexDir) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(indexDir + File.separator + "EmbeddingsFile.txt"), StandardCharsets.UTF_8))) {
            pw.println("k=" + k + " docs=" + docEmb.length);
            for (int i = 0; i < docEmb.length; i++) {
                StringBuilder sb = new StringBuilder();
                sb.append(docNumByRow[i]).append(" | ").append(pmcidByRow[i]).append(" | ");
                for (int j = 0; j < k; j++) { if (j > 0) sb.append(','); sb.append(fmt(docEmb[i][j])); }
                pw.println(sb);
            }
        }
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(indexDir + File.separator + "LatentTermsFile.txt"), StandardCharsets.UTF_8))) {
            pw.println("k=" + k + " terms=" + stemByCol.length);
            for (int t = 0; t < stemByCol.length; t++) {
                StringBuilder sb = new StringBuilder();
                sb.append(stemByCol[t]).append(" | ").append(fmt(idf[t])).append(" | ");
                for (int j = 0; j < k; j++) { if (j > 0) sb.append(','); sb.append(fmt(termProj[t][j])); }
                pw.println(sb);
            }
        }
    }

    static SemanticModel load(String indexDir) {
        File ef = new File(indexDir + File.separator + "EmbeddingsFile.txt");
        File lf = new File(indexDir + File.separator + "LatentTermsFile.txt");
        if (!ef.exists() || !lf.exists()) return null;
        try {
            List<int[]>     dn = new ArrayList<>();
            List<String>    pm = new ArrayList<>();
            List<double[]>  de = new ArrayList<>();
            int k = 0;
            try (BufferedReader br = reader(ef)) {
                String header = br.readLine();
                k = parseK(header);
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(" \\| ");
                    if (p.length < 3) continue;
                    dn.add(new int[]{Integer.parseInt(p[0].trim())});
                    pm.add(p[1].trim());
                    de.add(parseVec(p[2].trim(), k));
                }
            }
            List<String>   st = new ArrayList<>();
            List<Double>   id = new ArrayList<>();
            List<double[]> tp = new ArrayList<>();
            try (BufferedReader br = reader(lf)) {
                int tk = parseK(br.readLine());
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(" \\| ");
                    if (p.length < 3) continue;
                    st.add(p[0].trim());
                    id.add(Double.parseDouble(p[1].trim()));
                    tp.add(parseVec(p[2].trim(), tk));
                }
            }
            int[]    docNums = new int[dn.size()];
            String[] pmcids  = new String[pm.size()];
            double[][] docEmb = new double[de.size()][];
            for (int i = 0; i < dn.size(); i++) { docNums[i] = dn.get(i)[0]; pmcids[i] = pm.get(i); docEmb[i] = de.get(i); }
            String[] stems = st.toArray(new String[0]);
            double[] idf   = new double[id.size()];
            for (int i = 0; i < id.size(); i++) idf[i] = id.get(i);
            double[][] termProj = tp.toArray(new double[0][]);
            return new SemanticModel(k, docNums, pmcids, docEmb, stems, idf, termProj);
        } catch (Exception e) {
            return null;
        }
    }

    static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
    }
    static int parseK(String header) {
        if (header == null) return 0;
        for (String tok : header.split("\\s+"))
            if (tok.startsWith("k=")) return Integer.parseInt(tok.substring(2));
        return 0;
    }
    static double[] parseVec(String csv, int k) {
        String[] parts = csv.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i]);
        return v;
    }
    static String fmt(double x) {
        if (x == 0) return "0";
        return String.format("%.6g", x);
    }
    static double dot(double[] a, double[] b) {
        double s = 0; int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static void normalize(double[] a) {
        double n = norm(a);
        if (n > 0) for (int i = 0; i < a.length; i++) a[i] /= n;
    }
}
