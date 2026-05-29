package queryeval;

import java.io.IOException;
import java.util.*;

public class QueryParser {

    static boolean isAdvanced(String q) {
        if (q == null || q.isEmpty()) return false;
        String spaced = " " + q + " ";
        if (spaced.contains(" AND ") || spaced.contains(" OR ") || spaced.contains(" NOT ")) return true;
        if (q.indexOf('(') >= 0 || q.indexOf('*') >= 0) return true;
        return q.matches(".*\"[^\"]+\"\\s*~\\d+.*");
    }

    static class Parsed {
        final List<QueryEvaluator.Result> results;
        final List<String> stems;
        final String mode;
        Parsed(List<QueryEvaluator.Result> results, List<String> stems, String mode) {
            this.results = results;
            this.stems   = stems;
            this.mode    = mode;
        }
    }

    private static class Tok {
        final String type;
        final String value;
        final int    window;
        Tok(String type, String value)             { this(type, value, 0); }
        Tok(String type, String value, int window) { this.type = type; this.value = value; this.window = window; }
    }

    private final List<Tok> tokens = new ArrayList<>();
    private int pos = 0;

    Parsed search(String raw, String model, int topK) throws IOException {
        tokenize(raw);
        Node root = hasMore() ? parseOr() : new EmptyNode();
        Set<Integer> docIds = root.eval();
        List<String> positive = new ArrayList<>();
        root.collectPositive(positive);
        List<QueryEvaluator.Result> results = QueryEvaluator.rankWithinSet(docIds, positive, model, topK);
        return new Parsed(results, positive, describeMode());
    }

    private String describeMode() {
        Set<String> feats = new LinkedHashSet<>();
        for (Tok t : tokens) {
            switch (t.type) {
                case "AND": case "OR": case "NOT": feats.add("boolean");   break;
                case "PROX":                       feats.add("proximity"); break;
                case "PHRASE":                     feats.add("phrase");    break;
                case "WORD": if (t.value.endsWith("*")) feats.add("wildcard"); break;
                default: break;
            }
        }
        return feats.isEmpty() ? "simple" : String.join("+", feats);
    }

    private void tokenize(String q) {
        int i = 0, n = q.length();
        while (i < n) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') { tokens.add(new Tok("LPAREN", "(")); i++; continue; }
            if (c == ')') { tokens.add(new Tok("RPAREN", ")")); i++; continue; }
            if (c == '"') {
                int end = q.indexOf('"', i + 1);
                if (end < 0) end = n;
                String phrase = q.substring(i + 1, Math.min(end, n));
                i = Math.min(end + 1, n);
                int j = i;
                while (j < n && Character.isWhitespace(q.charAt(j))) j++;
                if (j < n && q.charAt(j) == '~') {
                    int k = j + 1, start = j + 1;
                    while (k < n && Character.isDigit(q.charAt(k))) k++;
                    if (k > start) {
                        tokens.add(new Tok("PROX", phrase, Integer.parseInt(q.substring(start, k))));
                        i = k;
                        continue;
                    }
                }
                tokens.add(new Tok("PHRASE", phrase));
                continue;
            }
            int start = i;
            while (i < n) {
                char d = q.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == '"') break;
                i++;
            }
            String w = q.substring(start, i);
            if      (w.equalsIgnoreCase("AND")) tokens.add(new Tok("AND", w));
            else if (w.equalsIgnoreCase("OR"))  tokens.add(new Tok("OR",  w));
            else if (w.equalsIgnoreCase("NOT")) tokens.add(new Tok("NOT", w));
            else                                tokens.add(new Tok("WORD", w));
        }
    }

    private boolean hasMore()           { return pos < tokens.size(); }
    private boolean peek(String type)   { return hasMore() && tokens.get(pos).type.equals(type); }
    private Tok     next()              { return hasMore() ? tokens.get(pos++) : null; }

    private Node parseOr() throws IOException {
        Node left = parseAnd();
        while (peek("OR")) { next(); left = or(left, parseAnd()); }
        return left;
    }

    private Node parseAnd() throws IOException {
        Node left = parseUnary();
        while (hasMore() && !peek("OR") && !peek("RPAREN")) {
            if (peek("AND")) next();
            if (!hasMore() || peek("OR") || peek("RPAREN")) break;
            left = and(left, parseUnary());
        }
        return left;
    }

    private Node parseUnary() throws IOException {
        if (peek("NOT")) { next(); return new NotNode(parseUnary()); }
        return parseAtom();
    }

    private Node parseAtom() throws IOException {
        Tok t = next();
        if (t == null) return new EmptyNode();
        switch (t.type) {
            case "LPAREN": {
                Node inner = parseOr();
                if (peek("RPAREN")) next();
                return inner;
            }
            case "PHRASE": return new PhraseNode(QueryEvaluator.processQuery(t.value));
            case "PROX":   return new ProximityNode(QueryEvaluator.processQuery(t.value), t.window);
            case "WORD": {
                String w = t.value;
                if (w.endsWith("*") && w.length() > 1)
                    return new WildcardNode(w.substring(0, w.length() - 1));
                List<String> stems = QueryEvaluator.processQuery(w);
                return stems.isEmpty() ? new EmptyNode() : new StemNode(stems.get(0));
            }
            default: return new EmptyNode();
        }
    }

    private static Node and(Node a, Node b) {
        if (a instanceof EmptyNode) return b;
        if (b instanceof EmptyNode) return a;
        return new AndNode(a, b);
    }

    private static Node or(Node a, Node b) {
        if (a instanceof EmptyNode) return b;
        if (b instanceof EmptyNode) return a;
        return new OrNode(a, b);
    }

    private abstract static class Node {
        abstract Set<Integer> eval() throws IOException;
        abstract void collectPositive(List<String> out);
    }

    private static class EmptyNode extends Node {
        Set<Integer> eval() { return new HashSet<>(); }
        void collectPositive(List<String> out) {}
    }

    private static class StemNode extends Node {
        final String stem;
        StemNode(String stem) { this.stem = stem; }
        Set<Integer> eval() throws IOException { return QueryEvaluator.termDocIds(stem); }
        void collectPositive(List<String> out) { out.add(stem); }
    }

    private static class WildcardNode extends Node {
        final String prefix;
        WildcardNode(String prefix) { this.prefix = prefix; }
        Set<Integer> eval() throws IOException { return QueryEvaluator.wildcardDocIds(prefix); }
        void collectPositive(List<String> out) { out.addAll(QueryEvaluator.wildcardStems(prefix)); }
    }

    private static class PhraseNode extends Node {
        final List<String> stems;
        PhraseNode(List<String> stems) { this.stems = stems; }
        Set<Integer> eval() throws IOException { return QueryEvaluator.phraseDocIds(stems); }
        void collectPositive(List<String> out) { out.addAll(stems); }
    }

    private static class ProximityNode extends Node {
        final List<String> stems;
        final int window;
        ProximityNode(List<String> stems, int window) { this.stems = stems; this.window = window; }
        Set<Integer> eval() throws IOException { return QueryEvaluator.proximityDocIds(stems, window); }
        void collectPositive(List<String> out) { out.addAll(stems); }
    }

    private static class NotNode extends Node {
        final Node child;
        NotNode(Node child) { this.child = child; }
        Set<Integer> eval() throws IOException {
            Set<Integer> all = QueryEvaluator.allDocIds();
            all.removeAll(child.eval());
            return all;
        }
        void collectPositive(List<String> out) {}
    }

    private static class AndNode extends Node {
        final Node left, right;
        AndNode(Node left, Node right) { this.left = left; this.right = right; }
        Set<Integer> eval() throws IOException {
            Set<Integer> a = left.eval();
            a.retainAll(right.eval());
            return a;
        }
        void collectPositive(List<String> out) { left.collectPositive(out); right.collectPositive(out); }
    }

    private static class OrNode extends Node {
        final Node left, right;
        OrNode(Node left, Node right) { this.left = left; this.right = right; }
        Set<Integer> eval() throws IOException {
            Set<Integer> a = left.eval();
            a.addAll(right.eval());
            return a;
        }
        void collectPositive(List<String> out) { left.collectPositive(out); right.collectPositive(out); }
    }
}
