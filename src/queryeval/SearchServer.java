package queryeval;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SearchServer {

    static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        mitos.stemmer.Stemmer.Initialize();

        String base = QueryEvaluator.resolveBaseDir();
        QueryEvaluator.INDEX_DIR    = base + File.separator + "CollectionIndex";
        QueryEvaluator.STOPWORDS_EN = base + File.separator + "Stopwords" + File.separator + "stopwordsEn.txt";
        QueryEvaluator.STOPWORDS_GR = base + File.separator + "Stopwords" + File.separator + "stopwordsGr.txt";

        QueryEvaluator.loadStopwords(QueryEvaluator.STOPWORDS_EN);
        QueryEvaluator.loadStopwords(QueryEvaluator.STOPWORDS_GR);
        QueryEvaluator.loadVocabulary();
        QueryEvaluator.countDocuments();

        System.out.println("Index loaded: " + QueryEvaluator.vocabulary.size()
                + " terms | " + QueryEvaluator.totalDocs + " docs");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/search", SearchServer::handleSearch);
        server.createContext("/",       SearchServer::handleUI);
        server.start();

        System.out.println("MedIR running at http://localhost:" + PORT);
    }

    static void handleSearch(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String q     = params.getOrDefault("q", "").trim();
        String type  = params.getOrDefault("type", "all");
        String model = params.getOrDefault("model", "bm25");
        int topK = 10;
        try { topK = Integer.parseInt(params.get("topk")); } catch (Exception ignored) {}

        if (q.isEmpty()) { respond(ex, 400, "{\"error\":\"missing query\"}"); return; }

        try {
            long t0 = System.currentTimeMillis();
            List<String> stems = QueryEvaluator.processQuery(q);

            int fetchK = "all".equalsIgnoreCase(type) ? topK : topK * 20;
            List<QueryEvaluator.Result> results = "bm25".equalsIgnoreCase(model)
                ? QueryEvaluator.searchBM25(stems, fetchK)
                : QueryEvaluator.searchVSM(stems, fetchK);
            results = QueryEvaluator.filterByType(results, "all".equalsIgnoreCase(type) ? null : type, topK);
            long elapsed = System.currentTimeMillis() - t0;

            StringBuilder sb = new StringBuilder();
            sb.append("{\"query\":\"").append(je(q)).append("\"")
              .append(",\"stems\":").append(toArr(stems))
              .append(",\"model\":\"").append(je(model)).append("\"")
              .append(",\"type\":\"").append(je(type)).append("\"")
              .append(",\"elapsed_ms\":").append(elapsed)
              .append(",\"results\":[");

            for (int i = 0; i < results.size(); i++) {
                if (i > 0) sb.append(",");
                QueryEvaluator.Result r = results.get(i);
                sb.append("{\"rank\":").append(i + 1)
                  .append(",\"pmcid\":\"").append(je(r.pmcid)).append("\"")
                  .append(",\"score\":").append(String.format("%.6f", r.score))
                  .append(",\"snippet\":\"").append(je(QueryEvaluator.getSnippet(r.path, stems))).append("\"}");
            }
            sb.append("]}");

            respond(ex, 200, sb.toString());

        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + je(e.getMessage()) + "\"}");
        }
    }

    static void handleUI(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = buildUI().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String buildUI() {
        return "<!DOCTYPE html><html lang='en'><head>"
            + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>MedIR</title>"
            + "<style>"
            + "*{box-sizing:border-box;margin:0;padding:0}"
            + "body{font-family:system-ui,-apple-system,sans-serif;background:#f5f6fa;color:#222}"
            + "header{background:#1a5fa8;color:#fff;padding:18px 24px}"
            + "header h1{font-size:1.4em;font-weight:700;letter-spacing:.5px}"
            + "header p{font-size:.85em;opacity:.8;margin-top:2px}"
            + ".container{max-width:900px;margin:28px auto;padding:0 16px}"
            + ".search-bar{background:#fff;border-radius:10px;padding:16px 18px;"
            + "  box-shadow:0 1px 4px rgba(0,0,0,.1);display:flex;gap:8px;flex-wrap:wrap}"
            + ".search-bar input{flex:1;min-width:200px;padding:9px 12px;border:1px solid #ddd;"
            + "  border-radius:6px;font-size:.95em;outline:none}"
            + ".search-bar input:focus{border-color:#1a5fa8}"
            + ".search-bar select{padding:9px 10px;border:1px solid #ddd;border-radius:6px;font-size:.9em}"
            + ".search-bar button{padding:9px 22px;background:#1a5fa8;color:#fff;border:none;"
            + "  border-radius:6px;cursor:pointer;font-size:.95em;font-weight:500}"
            + ".search-bar button:hover{background:#154e8f}"
            + "#status{font-size:.85em;color:#888;margin:14px 2px 10px}"
            + ".result{background:#fff;border-radius:8px;padding:14px 16px;margin-bottom:10px;"
            + "  box-shadow:0 1px 3px rgba(0,0,0,.07);border-left:3px solid transparent;transition:.15s}"
            + ".result:hover{border-left-color:#1a5fa8}"
            + ".result-rank{font-size:.75em;color:#aaa;margin-bottom:3px}"
            + ".result-pmcid{font-weight:600;font-size:1em;color:#1a5fa8;margin-bottom:3px}"
            + ".result-score{font-size:.78em;color:#888;margin-bottom:7px}"
            + ".result-snippet{font-size:.9em;color:#444;line-height:1.55}"
            + ".badge{display:inline-block;padding:1px 7px;border-radius:4px;font-size:.72em;"
            + "  font-weight:600;margin-left:6px;vertical-align:middle}"
            + ".badge-bm25{background:#e8f0fe;color:#1a5fa8}"
            + ".badge-vsm{background:#fce8e6;color:#c5221f}"
            + "</style></head><body>"
            + "<header><h1>MedIR</h1><p>Medical Information Retrieval — VSM &amp; BM25</p></header>"
            + "<div class='container'>"
            + "<div class='search-bar'>"
            + "<input type='text' id='q' placeholder='e.g. chest pain diagnosis' onkeydown='if(event.key===\"Enter\")search()'>"
            + "<select id='type'><option value='all'>All types</option>"
            + "<option value='diagnosis'>Diagnosis</option><option value='test'>Test</option>"
            + "<option value='treatment'>Treatment</option></select>"
            + "<select id='model'><option value='bm25'>BM25</option><option value='vsm'>VSM</option></select>"
            + "<button onclick='search()'>Search</button>"
            + "</div>"
            + "<div id='status'></div>"
            + "<div id='results'></div>"
            + "</div>"
            + "<script>"
            + "async function search(){"
            + "const q=document.getElementById('q').value.trim();"
            + "const type=document.getElementById('type').value;"
            + "const model=document.getElementById('model').value;"
            + "if(!q)return;"
            + "document.getElementById('status').textContent='Searching...';"
            + "document.getElementById('results').innerHTML='';"
            + "try{"
            + "const r=await fetch('/search?q='+encodeURIComponent(q)+'&type='+type+'&model='+model+'&topk=10');"
            + "const d=await r.json();"
            + "if(d.error){document.getElementById('status').textContent='Error: '+d.error;return;}"
            + "document.getElementById('status').textContent="
            + "d.results.length+' result(s)  ·  stems: '+d.stems.join(', ')+'  ·  '+d.elapsed_ms+'ms';"
            + "const badge=`<span class='badge badge-${d.model}'>${d.model.toUpperCase()}</span>`;"
            + "document.getElementById('results').innerHTML=d.results.map(r=>"
            + "`<div class='result'>"
            + "<div class='result-rank'>#${r.rank}</div>"
            + "<div class='result-pmcid'>${r.pmcid}${badge}</div>"
            + "<div class='result-score'>score: ${r.score.toFixed(5)}</div>"
            + "<div class='result-snippet'>${r.snippet}</div>"
            + "</div>`).join('');"
            + "}catch(e){document.getElementById('status').textContent='Error: '+e.message;}"
            + "}"
            + "</script></body></html>";
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                try { map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8")); }
                catch (Exception ignored) {}
            }
        }
        return map;
    }

    static String je(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "").replace("\t", " ");
    }

    static String toArr(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(je(list.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
