package queryeval;

import java.nio.file.*;
import java.util.*;

// Small home-grown test suite, no JUnit. Exits with 1 on any failure so CI fails too.
// Run: java -cp out:libs queryeval.TestRunner
public class TestRunner {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("MedIR test suite\n");
        testEditDistance();
        testVectorMath();
        testRRFConstant();
        testReciprocalRankFusion();
        testMetrics();
        testLSA();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    static void testEditDistance() {
        section("edit distance (spelling correction)");
        check("kitten->sitting = 3", QueryEvaluator.editDistance("kitten", "sitting") == 3);
        check("identical = 0",        QueryEvaluator.editDistance("abc", "abc") == 0);
        check("empty->abc = 3",       QueryEvaluator.editDistance("", "abc") == 3);
        check("ab->ba = 2",           QueryEvaluator.editDistance("ab", "ba") == 2);
    }

    static void testVectorMath() {
        section("vector math");
        double[] a = {3, 4};
        near("norm({3,4}) = 5", SemanticModel.norm(a), 5.0);
        near("dot orthogonal = 0", SemanticModel.dot(new double[]{1, 0}, new double[]{0, 1}), 0.0);
        double[] u = {6, 8};
        SemanticModel.normalize(u);
        near("normalized length = 1", SemanticModel.norm(u), 1.0);
    }

    static void testRRFConstant() {
        section("hybrid fusion constant");
        check("RRF_K == 60", QueryEvaluator.RRF_K == 60);
    }

    static void testReciprocalRankFusion() {
        section("reciprocal rank fusion");
        // doc "1" is top of both lists; doc "2" only high in one -> 1 must win.
        List<String> lex = Arrays.asList("1", "2", "3");
        List<String> sem = Arrays.asList("1", "4", "5");
        Map<String, Double> fused = new HashMap<>();
        for (int i = 0; i < lex.size(); i++) fused.merge(lex.get(i), 1.0 / (60 + i + 1), Double::sum);
        for (int i = 0; i < sem.size(); i++) fused.merge(sem.get(i), 1.0 / (60 + i + 1), Double::sum);
        String top = Collections.max(fused.entrySet(), Map.Entry.comparingByValue()).getKey();
        check("agreed-upon doc ranks first", top.equals("1"));
        check("fused score is sum of both ranks", Math.abs(fused.get("1") - (2.0 / 61)) < 1e-9);
    }

    static void testMetrics() {
        section("IR evaluation metrics");
        List<QueryEvaluator.Result> results = Arrays.asList(
                new QueryEvaluator.Result("/p/A.nxml", "A", 9.0),   // relevant
                new QueryEvaluator.Result("/p/X.nxml", "X", 8.0),   // not
                new QueryEvaluator.Result("/p/B.nxml", "B", 7.0));  // relevant
        Map<String, Integer> qrels = new HashMap<>();
        qrels.put("A", 1); qrels.put("B", 1); qrels.put("C", 1);   // 3 relevant total
        IRQualityEvaluator.TopicMetrics m =
                IRQualityEvaluator.computeMetrics(1, "diagnosis", results, qrels, 10);
        check("numRel = 3", m.numRel == 3);
        check("numRelRet = 2", m.numRelRet == 2);
        near("P@5 = 0.4", m.p5, 0.4);
        near("AP = (1 + 2/3)/3", m.ap, (1.0 + 2.0 / 3.0) / 3.0);
        near("R-Prec = 2/3", m.rPrec, 2.0 / 3.0);
    }

    static void testLSA() throws Exception {
        section("LSA: truncated SVD + fold-in");
        // Two clear topics: {A,B} and {C,D}. Query A must retrieve the A,B docs.
        String[] stems = {"a", "b", "c", "d"};
        double[] idf   = {1, 1, 1, 1};
        int[][]    rowCols = {{0, 1}, {0, 1}, {2, 3}, {2, 3}};
        double[][] rowVals = {{1, 1}, {1, 1}, {1, 1}, {1, 1}};
        int[]    docNums = {0, 1, 2, 3};
        String[] pmcids  = {"p0", "p1", "p2", "p3"};

        Path tmp = Files.createTempDirectory("medir-test");
        SemanticModel m = SemanticModel.build(tmp.toString(), docNums, pmcids, stems, idf,
                rowCols, rowVals, 2);
        check("rank recovered (k>=2)", m.k >= 2);

        List<double[]> ra = m.rankScored(Arrays.asList("a"), 4);
        check("query 'a' returns docs", !ra.isEmpty());
        check("top doc for 'a' is in {0,1}", (int) ra.get(0)[0] == 0 || (int) ra.get(0)[0] == 1);

        List<double[]> rc = m.rankScored(Arrays.asList("c"), 4);
        check("top doc for 'c' is in {2,3}", !rc.isEmpty()
                && ((int) rc.get(0)[0] == 2 || (int) rc.get(0)[0] == 3));

        // persistence round-trip
        SemanticModel loaded = SemanticModel.load(tmp.toString());
        check("model reloads from disk", loaded != null && loaded.k == m.k);
        List<double[]> rl = loaded.rankScored(Arrays.asList("a"), 4);
        check("reloaded model ranks consistently",
                !rl.isEmpty() && ((int) rl.get(0)[0] == 0 || (int) rl.get(0)[0] == 1));

        // 2D map + clustering sanity
        double[][] pts = m.map2D();
        check("map2D returns a point per doc", pts.length == 4);
        int[] assign = m.kmeans(2, 20);
        check("kmeans separates the two topics", assign[0] == assign[1] && assign[2] == assign[3]
                && assign[0] != assign[2]);

        try { Files.walk(tmp).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); }
        catch (Exception ignored) {}
    }

    static void section(String name) { System.out.println("[" + name + "]"); }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else      { failed++; System.out.println("  FAIL  " + name); }
    }

    static void near(String name, double actual, double expected) {
        check(name + "  (got " + String.format("%.4f", actual) + ")", Math.abs(actual - expected) < 1e-3);
    }
}
