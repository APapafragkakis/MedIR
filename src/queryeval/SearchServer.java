package queryeval;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

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
        server.createContext("/search",  SearchServer::handleSearch);
        server.createContext("/suggest", SearchServer::handleSuggest);
        server.createContext("/similar", SearchServer::handleSimilar);
        server.createContext("/stats",   SearchServer::handleStats);
        server.createContext("/",        SearchServer::handleUI);
        server.start();

        System.out.println("MedIR running at http://localhost:" + PORT);
    }

    // -------------------------------------------------------------------------
    // /search
    // -------------------------------------------------------------------------

    static void handleSearch(HttpExchange ex) throws IOException {
        cors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String  q      = params.getOrDefault("q", "").trim();
        String  type   = params.getOrDefault("type",  "all");
        String  model  = params.getOrDefault("model", "bm25");
        boolean expand = "true".equals(params.get("expand"));
        int     topK   = 10;
        try { topK = Integer.parseInt(params.get("topk")); } catch (Exception ignored) {}

        if (q.isEmpty()) { respond(ex, 400, "{\"error\":\"missing query\"}"); return; }

        try {
            long t0 = System.currentTimeMillis();

            // Parse phrases + bag-of-words
            List<List<String>> phrases = new ArrayList<>();
            List<String>       stems   = QueryEvaluator.extractPhrases(q, phrases);

            // Collect OOV info
            Map<String, List<String>> oovInfo = QueryEvaluator.getOovInfo(stems);

            // Rocchio expansion
            List<String> expandedTerms = Collections.emptyList();
            if (expand && !stems.isEmpty()) {
                List<String> expanded = QueryEvaluator.expandQueryRocchio(
                        stems,
                        QueryEvaluator.ROCCHIO_FEEDBACK_DOCS,
                        QueryEvaluator.ROCCHIO_EXPAND_TERMS);
                expandedTerms = expanded.subList(stems.size(), expanded.size());
                stems = expanded;
            }

            int fetchK = "all".equalsIgnoreCase(type) ? topK : topK * 20;
            List<QueryEvaluator.Result> results =
                    QueryEvaluator.runCombinedSearch(stems, phrases, model, fetchK);
            results = QueryEvaluator.filterByType(
                    results, "all".equalsIgnoreCase(type) ? null : type, topK);

            long elapsed = System.currentTimeMillis() - t0;

            // All stems used (original + expanded + phrase stems)
            List<String> allStems = new ArrayList<>(stems);
            for (List<String> ph : phrases) allStems.addAll(ph);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"query\":\"").append(je(q)).append("\"")
              .append(",\"stems\":").append(toArr(stems))
              .append(",\"expanded_terms\":").append(toArr(expandedTerms))
              .append(",\"phrases\":").append(phrasesToArr(phrases))
              .append(",\"model\":\"").append(je(model)).append("\"")
              .append(",\"type\":\"").append(je(type)).append("\"")
              .append(",\"elapsed_ms\":").append(elapsed)
              .append(",\"oov\":").append(oovToJson(oovInfo))
              .append(",\"results\":[");

            for (int i = 0; i < results.size(); i++) {
                if (i > 0) sb.append(",");
                QueryEvaluator.Result r = results.get(i);
                String docType = detectType(r.path);
                String snippet = QueryEvaluator.getHighlightedSnippet(r.path, allStems);
                sb.append("{\"rank\":").append(i + 1)
                  .append(",\"pmcid\":\"").append(je(r.pmcid)).append("\"")
                  .append(",\"score\":").append(String.format("%.6f", r.score))
                  .append(",\"type\":\"").append(je(docType)).append("\"")
                  .append(",\"snippet\":\"").append(je(snippet)).append("\"}");
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());

        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + je(e.getMessage()) + "\"}");
        }
    }

    // -------------------------------------------------------------------------
    // /suggest?prefix=...
    // -------------------------------------------------------------------------

    static void handleSuggest(HttpExchange ex) throws IOException {
        cors(ex);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String prefix = params.getOrDefault("prefix", params.getOrDefault("q", "")).trim();

        List<String> suggestions = QueryEvaluator.getAutocompleteSuggestions(prefix, 8);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(je(suggestions.get(i))).append("\"");
        }
        sb.append("]");
        respond(ex, 200, sb.toString());
    }

    // -------------------------------------------------------------------------
    // /similar?pmcid=...
    // -------------------------------------------------------------------------

    static void handleSimilar(HttpExchange ex) throws IOException {
        cors(ex);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String pmcid = params.getOrDefault("pmcid", "").trim();
        int topK = 5;
        try { topK = Integer.parseInt(params.get("topk")); } catch (Exception ignored) {}

        if (pmcid.isEmpty()) { respond(ex, 400, "{\"error\":\"missing pmcid\"}"); return; }

        try {
            List<QueryEvaluator.Result> results = QueryEvaluator.getSimilarDocs(pmcid, topK);
            StringBuilder sb = new StringBuilder("{\"pmcid\":\"").append(je(pmcid)).append("\",\"similar\":[");
            for (int i = 0; i < results.size(); i++) {
                if (i > 0) sb.append(",");
                QueryEvaluator.Result r = results.get(i);
                sb.append("{\"rank\":").append(i + 1)
                  .append(",\"pmcid\":\"").append(je(r.pmcid)).append("\"")
                  .append(",\"score\":").append(String.format("%.6f", r.score))
                  .append(",\"type\":\"").append(je(detectType(r.path))).append("\"}");
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + je(e.getMessage()) + "\"}");
        }
    }

    // -------------------------------------------------------------------------
    // /stats
    // -------------------------------------------------------------------------

    static void handleStats(HttpExchange ex) throws IOException {
        cors(ex);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        // Top 12 terms by df
        List<Map.Entry<String, QueryEvaluator.VocabEntry>> topTerms =
            QueryEvaluator.vocabulary.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, QueryEvaluator.VocabEntry> e)
                        -> e.getValue().df).reversed())
                .limit(12)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("{\"terms\":").append(QueryEvaluator.vocabulary.size())
          .append(",\"docs\":").append(QueryEvaluator.totalDocs)
          .append(",\"avg_doc_len\":").append(String.format("%.1f", QueryEvaluator.avgDocLen))
          .append(",\"top_terms\":[");
        for (int i = 0; i < topTerms.size(); i++) {
            if (i > 0) sb.append(",");
            Map.Entry<String, QueryEvaluator.VocabEntry> e = topTerms.get(i);
            sb.append("{\"word\":\"").append(je(e.getKey()))
              .append("\",\"df\":").append(e.getValue().df).append("}");
        }
        sb.append("]}");
        respond(ex, 200, sb.toString());
    }

    // -------------------------------------------------------------------------
    // / (web UI)
    // -------------------------------------------------------------------------

    static void handleUI(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = buildUI().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // -------------------------------------------------------------------------
    // Web UI
    // -------------------------------------------------------------------------

    static String buildUI() {
        return "<!DOCTYPE html><html lang='en'><head>"
            + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>MedIR</title>"
            + "<style>"
            + ":root{--blue:#1a5fa8;--blue-dark:#154e8f;--blue-light:#e8f3ff;"
            + "  --red:#c5221f;--red-light:#fce8e6;"
            + "  --green:#1e8c45;--green-light:#e6f4ea;"
            + "  --mark:#fbbc04;"
            + "  --bg:#f1f3f6;--card:#fff;--border:#dde1e7;--text:#1f2328;--muted:#6e7781}"
            + "*{box-sizing:border-box;margin:0;padding:0}"
            + "body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:var(--bg);color:var(--text)}"
            + "a{color:var(--blue);text-decoration:none}"
            // Header
            + "header{background:linear-gradient(135deg,#0f3f75 0%,var(--blue) 60%,#2176c7 100%);"
            + "  color:#fff;padding:20px 28px 18px;box-shadow:0 2px 8px rgba(0,0,0,.25)}"
            + "header h1{font-size:1.55em;font-weight:700;letter-spacing:.3px}"
            + "header p{font-size:.82em;opacity:.75;margin-top:3px}"
            // Search area
            + ".search-wrap{max-width:860px;margin:28px auto 0;padding:0 16px}"
            + ".search-card{background:var(--card);border-radius:12px;padding:18px 20px;"
            + "  box-shadow:0 2px 10px rgba(0,0,0,.09)}"
            + ".input-row{display:flex;gap:8px;position:relative}"
            + ".input-row input{flex:1;padding:11px 14px;border:1.5px solid var(--border);"
            + "  border-radius:8px;font-size:1em;outline:none;transition:.15s}"
            + ".input-row input:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(26,95,168,.12)}"
            + ".btn-search{padding:11px 26px;background:var(--blue);color:#fff;border:none;"
            + "  border-radius:8px;font-size:.95em;font-weight:600;cursor:pointer;transition:.15s;white-space:nowrap}"
            + ".btn-search:hover{background:var(--blue-dark)}"
            + ".controls{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;align-items:center}"
            + ".controls select{padding:7px 10px;border:1.5px solid var(--border);border-radius:6px;"
            + "  font-size:.875em;background:var(--card);cursor:pointer}"
            + ".chk-label{font-size:.875em;color:var(--muted);display:flex;align-items:center;gap:5px;"
            + "  cursor:pointer;user-select:none}"
            + ".chk-label input{accent-color:var(--blue)}"
            + ".phrase-hint{font-size:.78em;color:var(--muted);margin-top:6px}"
            // Autocomplete
            + ".ac-dropdown{position:absolute;top:calc(100% + 4px);left:0;right:70px;"
            + "  background:var(--card);border:1px solid var(--border);border-radius:8px;"
            + "  box-shadow:0 4px 16px rgba(0,0,0,.12);z-index:100;overflow:hidden;display:none}"
            + ".ac-item{padding:9px 14px;cursor:pointer;font-size:.9em;display:flex;align-items:center;gap:8px}"
            + ".ac-item:hover,.ac-item.active{background:var(--blue-light)}"
            + ".ac-icon{width:14px;height:14px;opacity:.4}"
            // Main layout
            + ".main-wrap{max-width:860px;margin:16px auto 40px;padding:0 16px;"
            + "  display:grid;grid-template-columns:1fr 260px;gap:16px}"
            + "@media(max-width:700px){.main-wrap{grid-template-columns:1fr}}"
            // Status bar
            + ".status{font-size:.83em;color:var(--muted);margin-bottom:12px;min-height:1.2em}"
            // Banners
            + ".banner{background:var(--blue-light);border:1px solid #c2d9f5;border-radius:8px;"
            + "  padding:10px 14px;font-size:.875em;margin-bottom:12px;display:none}"
            + ".banner.oov{background:#fff8e1;border-color:#ffe082}"
            + ".expand-banner{background:var(--green-light);border-color:#b7dfca}"
            // Result card
            + ".result{background:var(--card);border-radius:10px;padding:15px 17px;"
            + "  margin-bottom:10px;box-shadow:0 1px 4px rgba(0,0,0,.07);"
            + "  border-left:3px solid transparent;transition:.15s}"
            + ".result:hover{border-left-color:var(--blue);box-shadow:0 2px 10px rgba(0,0,0,.1)}"
            + ".result-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:4px}"
            + ".rank{width:24px;height:24px;border-radius:50%;background:var(--blue-light);"
            + "  color:var(--blue);font-size:.72em;font-weight:700;display:flex;align-items:center;"
            + "  justify-content:center;flex-shrink:0}"
            + ".pmcid{font-weight:600;font-size:1em;color:var(--blue)}"
            + ".pmcid:hover{text-decoration:underline}"
            + ".score{font-size:.78em;color:var(--muted);margin-left:auto}"
            + ".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:.7em;"
            + "  font-weight:600;text-transform:uppercase;letter-spacing:.3px}"
            + ".badge-bm25{background:var(--blue-light);color:var(--blue)}"
            + ".badge-vsm{background:var(--red-light);color:var(--red)}"
            + ".badge-diagnosis{background:#f0f4ff;color:#3d5af1}"
            + ".badge-test{background:#f5f0ff;color:#7c3aed}"
            + ".badge-treatment{background:var(--green-light);color:var(--green)}"
            + ".snippet{font-size:.875em;color:#3d4347;line-height:1.6;margin-top:8px}"
            + ".snippet mark{background:#fff59d;border-radius:2px;padding:0 1px;font-style:normal}"
            + ".result-footer{margin-top:10px;display:flex;gap:8px}"
            + ".btn-sim{padding:4px 12px;font-size:.78em;border:1px solid var(--border);"
            + "  border-radius:5px;background:var(--card);cursor:pointer;color:var(--muted);"
            + "  transition:.12s}"
            + ".btn-sim:hover{background:var(--blue-light);border-color:var(--blue);color:var(--blue)}"
            // Stats sidebar
            + ".sidebar{grid-row:1/3}"
            + ".stat-card{background:var(--card);border-radius:10px;padding:15px 16px;"
            + "  box-shadow:0 1px 4px rgba(0,0,0,.07);margin-bottom:12px}"
            + ".stat-card h3{font-size:.85em;font-weight:600;color:var(--muted);"
            + "  text-transform:uppercase;letter-spacing:.5px;margin-bottom:10px}"
            + ".stat-row{display:flex;justify-content:space-between;font-size:.85em;padding:3px 0;"
            + "  border-bottom:1px solid #f0f1f3}"
            + ".stat-row:last-child{border-bottom:none}"
            + ".stat-val{font-weight:600;color:var(--text)}"
            + ".term-bar{margin-top:8px}"
            + ".term-row{display:flex;align-items:center;gap:6px;margin-bottom:5px;font-size:.8em}"
            + ".term-fill{height:6px;border-radius:3px;background:var(--blue);opacity:.6}"
            + ".term-name{min-width:70px;color:var(--text)}"
            + ".term-count{color:var(--muted);font-size:.9em}"
            // Modal (similar docs)
            + ".modal-backdrop{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:200;display:none;"
            + "  align-items:center;justify-content:center}"
            + ".modal-backdrop.open{display:flex}"
            + ".modal{background:var(--card);border-radius:12px;padding:24px 28px;"
            + "  max-width:500px;width:90%;box-shadow:0 8px 40px rgba(0,0,0,.25)}"
            + ".modal h2{font-size:1.05em;margin-bottom:14px;color:var(--text)}"
            + ".modal-close{float:right;background:none;border:none;font-size:1.3em;cursor:pointer;color:var(--muted)}"
            + ".sim-item{padding:10px 0;border-bottom:1px solid var(--border);"
            + "  display:flex;justify-content:space-between;align-items:center}"
            + ".sim-item:last-child{border-bottom:none}"
            // Pagination
            + ".pagination{display:flex;gap:6px;margin-top:14px;justify-content:center}"
            + ".page-btn{padding:6px 13px;border:1.5px solid var(--border);border-radius:6px;"
            + "  background:var(--card);cursor:pointer;font-size:.85em;transition:.12s}"
            + ".page-btn:hover,.page-btn.active{background:var(--blue);color:#fff;border-color:var(--blue)}"
            // No-results
            + ".empty{text-align:center;padding:40px 20px;color:var(--muted)}"
            + ".empty svg{width:48px;opacity:.25;margin-bottom:12px}"
            + "</style>"
            + "</head><body>"
            // Header
            + "<header>"
            + "<h1>MedIR</h1>"
            + "<p>Medical Information Retrieval &mdash; Full-text clinical literature search</p>"
            + "</header>"
            // Search area
            + "<div class='search-wrap'><div class='search-card'>"
            + "<div class='input-row'>"
            + "<input type='text' id='q' placeholder='Search clinical literature... (use \"quotes\" for phrase)'"
            + "  autocomplete='off' spellcheck='false'"
            + "  oninput='onInput()' onkeydown='onKey(event)'>"
            + "<div class='ac-dropdown' id='ac'></div>"
            + "<button class='btn-search' onclick='doSearch()'>Search</button>"
            + "</div>"
            + "<div class='controls'>"
            + "<select id='type'>"
            + "<option value='all'>All types</option>"
            + "<option value='diagnosis'>Diagnosis</option>"
            + "<option value='test'>Test</option>"
            + "<option value='treatment'>Treatment</option>"
            + "</select>"
            + "<select id='model'>"
            + "<option value='bm25'>BM25</option>"
            + "<option value='vsm'>VSM</option>"
            + "</select>"
            + "<select id='topk'>"
            + "<option value='10'>Top 10</option>"
            + "<option value='20'>Top 20</option>"
            + "<option value='50'>Top 50</option>"
            + "</select>"
            + "<label class='chk-label'><input type='checkbox' id='expand'>Query Expansion</label>"
            + "</div>"
            + "<p class='phrase-hint'>Tip: wrap terms in quotes for exact phrase search &mdash; e.g. <em>\"chest pain\"</em></p>"
            + "</div></div>"
            // Main layout
            + "<div class='main-wrap'>"
            + "<div>"
            + "<div id='oov-banner' class='banner oov'></div>"
            + "<div id='exp-banner' class='banner expand-banner'></div>"
            + "<div class='status' id='status'></div>"
            + "<div id='results'></div>"
            + "<div class='pagination' id='pager'></div>"
            + "</div>"
            // Sidebar
            + "<div class='sidebar'>"
            + "<div class='stat-card' id='idx-stats'><h3>Index</h3><p style='color:var(--muted);font-size:.85em'>Loading...</p></div>"
            + "<div class='stat-card' id='query-stats' style='display:none'>"
            + "<h3>Query Analysis</h3><div id='qa-content'></div>"
            + "</div>"
            + "</div>"
            + "</div>"
            // Modal
            + "<div class='modal-backdrop' id='modal' onclick='closeModal(event)'>"
            + "<div class='modal'>"
            + "<button class='modal-close' onclick='document.getElementById(\"modal\").classList.remove(\"open\")'>&times;</button>"
            + "<h2 id='modal-title'>Similar documents</h2>"
            + "<div id='modal-body'></div>"
            + "</div></div>"
            // Script
            + "<script>"
            // Global state
            + "let allResults=[];"
            + "let page=0;const PAGE=10;"
            + "let acTimer=null,acIdx=-1;"
            // Init: load stats
            + "fetch('/stats').then(r=>r.json()).then(d=>{"
            + "  let h='<div class=\"stat-row\"><span>Vocabulary</span><span class=\"stat-val\">'+d.terms.toLocaleString()+'</span></div>';"
            + "  h+='<div class=\"stat-row\"><span>Documents</span><span class=\"stat-val\">'+d.docs+'</span></div>';"
            + "  h+='<div class=\"stat-row\"><span>Avg doc length</span><span class=\"stat-val\">'+d.avg_doc_len+'</span></div>';"
            + "  h+='<div class=\"term-bar\">';"
            + "  const maxDf=d.top_terms[0]&&d.top_terms[0].df||1;"
            + "  d.top_terms.forEach(t=>{"
            + "    const w=Math.round(t.df/maxDf*100);"
            + "    h+='<div class=\"term-row\"><span class=\"term-name\">'+esc(t.word)+'</span>'"
            + "       +'<div class=\"term-fill\" style=\"width:'+w+'px\"></div>'"
            + "       +'<span class=\"term-count\">'+t.df+'</span></div>';"
            + "  });"
            + "  h+='</div>';"
            + "  document.getElementById('idx-stats').innerHTML='<h3>Index</h3>'+h;"
            + "}).catch(()=>{});"
            // Autocomplete
            + "function onInput(){"
            + "  clearTimeout(acTimer);"
            + "  acTimer=setTimeout(()=>{"
            + "    const q=document.getElementById('q').value.trim();"
            + "    const words=q.split(/\\s+/);const last=words[words.length-1];"
            + "    if(last.length<2){hideAc();return;}"
            + "    fetch('/suggest?prefix='+encodeURIComponent(last)).then(r=>r.json()).then(items=>{"
            + "      if(!items.length){hideAc();return;}"
            + "      const ac=document.getElementById('ac');"
            + "      ac.innerHTML=items.map((s,i)=>"
            + "        '<div class=\"ac-item\" onmousedown=\"pickAc(\\'' + s + '\\')\">'+"
            + "        '<svg class=\"ac-icon\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">'+"
            + "        '<circle cx=\"11\" cy=\"11\" r=\"8\"/><path d=\"m21 21-4.35-4.35\"/></svg>'+"
            + "        s+'</div>').join('');"
            + "      ac.style.display='block';acIdx=-1;"
            + "    }).catch(()=>{});"
            + "  },200);"
            + "}"
            + "function hideAc(){const ac=document.getElementById('ac');ac.style.display='none';ac.innerHTML='';}"
            + "function pickAc(s){"
            + "  const inp=document.getElementById('q');"
            + "  const words=inp.value.trim().split(/\\s+/);"
            + "  words[words.length-1]=s;"
            + "  inp.value=words.join(' ')+' ';"
            + "  hideAc();inp.focus();"
            + "}"
            + "function onKey(e){"
            + "  const ac=document.getElementById('ac');"
            + "  const items=ac.querySelectorAll('.ac-item');"
            + "  if(e.key==='ArrowDown'){e.preventDefault();acIdx=Math.min(acIdx+1,items.length-1);updateAcSel(items);}"
            + "  else if(e.key==='ArrowUp'){e.preventDefault();acIdx=Math.max(acIdx-1,-1);updateAcSel(items);}"
            + "  else if(e.key==='Enter'){if(acIdx>=0&&items[acIdx]){items[acIdx].dispatchEvent(new MouseEvent('mousedown'));}else{doSearch();}hideAc();}"
            + "  else if(e.key==='Escape'){hideAc();}"
            + "}"
            + "function updateAcSel(items){"
            + "  items.forEach((el,i)=>el.classList.toggle('active',i===acIdx));"
            + "}"
            + "document.addEventListener('click',e=>{"
            + "  if(!document.getElementById('ac').contains(e.target))hideAc();"
            + "});"
            // Search
            + "async function doSearch(pg){"
            + "  if(pg===undefined)pg=0;"
            + "  hideAc();"
            + "  const q=document.getElementById('q').value.trim();"
            + "  if(!q)return;"
            + "  const type=document.getElementById('type').value;"
            + "  const model=document.getElementById('model').value;"
            + "  const topk=document.getElementById('topk').value;"
            + "  const expand=document.getElementById('expand').checked;"
            + "  document.getElementById('status').textContent='Searching...';"
            + "  document.getElementById('results').innerHTML='';"
            + "  document.getElementById('pager').innerHTML='';"
            + "  document.getElementById('oov-banner').style.display='none';"
            + "  document.getElementById('exp-banner').style.display='none';"
            + "  const url='/search?q='+encodeURIComponent(q)+'&type='+type+'&model='+model+'&topk='+topk+'&expand='+expand;"
            + "  try{"
            + "    const resp=await fetch(url);"
            + "    const d=await resp.json();"
            + "    if(d.error){document.getElementById('status').textContent='Error: '+d.error;return;}"
            + "    allResults=d.results;page=pg;"
            + "    renderResults(d);"
            + "  }catch(e){document.getElementById('status').textContent='Error: '+e.message;}"
            + "}"
            + "function renderResults(d){"
            + "  const model=d.model;"
            + "  const badge='<span class=\"badge badge-'+model+'\">'+model.toUpperCase()+'</span>';"
            + "  // Status bar"
            + "  document.getElementById('status').innerHTML="
            + "    '<b>'+d.results.length+'</b> result(s)&nbsp;&nbsp;&middot;&nbsp;&nbsp;'"
            + "    +'stems: <em>'+d.stems.join(', ')+'</em>&nbsp;&nbsp;&middot;&nbsp;&nbsp;'"
            + "    +d.elapsed_ms+'ms&nbsp;&nbsp;'+badge;"
            + "  // OOV banner"
            + "  const oovDiv=document.getElementById('oov-banner');"
            + "  const oovKeys=Object.keys(d.oov||{});"
            + "  if(oovKeys.length){"
            + "    oovDiv.innerHTML='<b>Did you mean?</b> '+oovKeys.map(k=>"
            + "      'For <em>'+esc(k)+'</em>: '+(d.oov[k].slice(0,2).map(s=>"
            + "        '<a href=\"#\" onclick=\"useWord(event,\\'' + s + '\\')\">' + esc(s)+'</a>')"
            + "      .join(', '))"
            + "    ).join(' &nbsp;|&nbsp; ');"
            + "    oovDiv.style.display='block';"
            + "  }"
            + "  // Expansion banner"
            + "  const expDiv=document.getElementById('exp-banner');"
            + "  if(d.expanded_terms&&d.expanded_terms.length){"
            + "    expDiv.innerHTML='<b>Query expansion:</b> added &rarr; '+"
            + "      d.expanded_terms.map(t=>'<em>'+esc(t)+'</em>').join(', ');"
            + "    expDiv.style.display='block';"
            + "  }"
            + "  // Query analysis sidebar"
            + "  const qs=document.getElementById('query-stats');"
            + "  const qc=document.getElementById('qa-content');"
            + "  let qa='';"
            + "  qa+='<div class=\"stat-row\"><span>Model</span><span class=\"stat-val\">'+d.model.toUpperCase()+'</span></div>';"
            + "  qa+='<div class=\"stat-row\"><span>Stems</span><span class=\"stat-val\">'+d.stems.length+'</span></div>';"
            + "  if(d.phrases&&d.phrases.length)"
            + "    qa+='<div class=\"stat-row\"><span>Phrases</span><span class=\"stat-val\">'+d.phrases.length+'</span></div>';"
            + "  if(d.expanded_terms&&d.expanded_terms.length)"
            + "    qa+='<div class=\"stat-row\"><span>Expanded</span><span class=\"stat-val\">+'+d.expanded_terms.length+'</span></div>';"
            + "  qa+='<div class=\"stat-row\"><span>Retrieved</span><span class=\"stat-val\">'+d.results.length+'</span></div>';"
            + "  qa+='<div class=\"stat-row\"><span>Elapsed</span><span class=\"stat-val\">'+d.elapsed_ms+'ms</span></div>';"
            + "  qc.innerHTML=qa;qs.style.display='block';"
            + "  // Results"
            + "  if(!d.results.length){"
            + "    document.getElementById('results').innerHTML="
            + "      '<div class=\"empty\"><p>No results found.</p></div>';"
            + "    return;"
            + "  }"
            + "  const html=d.results.map(r=>{"
            + "    const typeBadge=r.type?'<span class=\"badge badge-'+r.type+'\">' + r.type+'</span>':'';"
            + "    const scoreStr=parseFloat(r.score).toFixed(4);"
            + "    const pmcUrl='https://www.ncbi.nlm.nih.gov/pmc/articles/PMC'+r.pmcid+'/';"
            + "    return `<div class='result'>`"
            + "      +`<div class='result-head'>`"
            + "      +`<div class='rank'>${r.rank}</div>`"
            + "      +`<a class='pmcid' href='${pmcUrl}' target='_blank'>PMC${r.pmcid}</a>`"
            + "      +typeBadge"
            + "      +`<span class='score'>${scoreStr}</span>`"
            + "      +`</div>`"
            + "      +`<div class='snippet'>${r.snippet}</div>`"
            + "      +`<div class='result-footer'>`"
            + "      +`<button class='btn-sim' onclick='showSimilar(\"${r.pmcid}\")'>Similar docs</button>`"
            + "      +`</div></div>`;"
            + "  }).join('');"
            + "  document.getElementById('results').innerHTML=html;"
            + "}"
            + "function useWord(ev,w){"
            + "  ev.preventDefault();"
            + "  const inp=document.getElementById('q');"
            + "  inp.value=w;doSearch();"
            + "}"
            // Similar docs modal
            + "async function showSimilar(pmcid){"
            + "  const modal=document.getElementById('modal');"
            + "  const title=document.getElementById('modal-title');"
            + "  const body=document.getElementById('modal-body');"
            + "  title.textContent='Similar to PMC'+pmcid;"
            + "  body.innerHTML='<p style=\"color:var(--muted);font-size:.9em\">Loading...</p>';"
            + "  modal.classList.add('open');"
            + "  try{"
            + "    const d=await(await fetch('/similar?pmcid='+pmcid+'&topk=6')).json();"
            + "    if(!d.similar||!d.similar.length){body.innerHTML='<p>No similar documents found.</p>';return;}"
            + "    body.innerHTML=d.similar.map(r=>{"
            + "      const url='https://www.ncbi.nlm.nih.gov/pmc/articles/PMC'+r.pmcid+'/';"
            + "      const typeBadge=r.type?'<span class=\"badge badge-'+r.type+'\">' +r.type+'</span>':'';"
            + "      return '<div class=\"sim-item\">'"
            + "        +'<div><a href=\"'+url+'\" target=\"_blank\">PMC'+esc(r.pmcid)+'</a> '+typeBadge+'</div>'"
            + "        +'<span style=\"color:var(--muted);font-size:.82em\">'+parseFloat(r.score).toFixed(3)+'</span>'"
            + "        +'</div>';"
            + "    }).join('');"
            + "  }catch(e){body.innerHTML='<p>Error loading similar documents.</p>';}"
            + "}"
            + "function closeModal(e){"
            + "  if(e.target===document.getElementById('modal'))"
            + "    document.getElementById('modal').classList.remove('open');"
            + "}"
            // CSV export
            + "function exportCSV(){"
            + "  if(!allResults.length)return;"
            + "  const rows=[['rank','pmcid','score','type','snippet']];"
            + "  allResults.forEach(r=>rows.push([r.rank,'PMC'+r.pmcid,r.score,r.type,r.snippet.replace(/<[^>]+>/g,'')]));"
            + "  const csv=rows.map(r=>r.map(v=>'\"'+String(v).replace(/\"/g,'\"\"')+'\"').join(',')).join('\\n');"
            + "  const a=document.createElement('a');"
            + "  a.href='data:text/csv;charset=utf-8,'+encodeURIComponent(csv);"
            + "  a.download='medIR_results.csv';a.click();"
            + "}"
            // Utility
            + "function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}"
            + "</script>"
            + "</body></html>";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static String detectType(String path) {
        if (path == null) return "";
        String lp = path.toLowerCase();
        if (lp.contains("/diagnosis/") || lp.contains("\\diagnosis\\")) return "diagnosis";
        if (lp.contains("/test/")      || lp.contains("\\test\\"))      return "test";
        if (lp.contains("/treatment/") || lp.contains("\\treatment\\")) return "treatment";
        return "";
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

    static String phrasesToArr(List<List<String>> phrases) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < phrases.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toArr(phrases.get(i)));
        }
        return sb.append("]").toString();
    }

    static String oovToJson(Map<String, List<String>> oov) {
        if (oov == null || oov.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : oov.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(je(e.getKey())).append("\":").append(toArr(e.getValue()));
        }
        return sb.append("}").toString();
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,OPTIONS");
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
