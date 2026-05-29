package queryeval;

import gr.uoc.csd.hy463.NXMLFileReader;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

public class QueryEvaluatorGUI extends JFrame {

    private JTextField    queryField;
    private JComboBox<String> typeBox;
    private JComboBox<String> modelBox;
    private JCheckBox     expandBox;
    private JButton       searchBtn;
    private DefaultListModel<QueryEvaluator.Result> listModel;
    private JList<QueryEvaluator.Result>             resultsList;
    private JEditorPane   contentPane;
    private JLabel        statusBar;

    private List<String>       lastStems   = new ArrayList<>();
    private final Map<String, String> snippetCache = new HashMap<>();

    public QueryEvaluatorGUI() {
        super("MedIR");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 700);
        setLocationRelativeTo(null);
        buildUI();
    }

    private void buildUI() {
        // ---- toolbar ----
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
        toolbar.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(210, 210, 220)),
            new EmptyBorder(2, 6, 2, 6)
        ));

        toolbar.add(new JLabel("Query:"));
        queryField = new JTextField(38);
        queryField.setToolTipText("Use \"quotes\" for phrase search, prefix + for expansion");
        queryField.addActionListener(e -> runSearch());
        toolbar.add(queryField);

        toolbar.add(new JLabel("Type:"));
        typeBox = new JComboBox<>(new String[]{"All", "Diagnosis", "Test", "Treatment"});
        toolbar.add(typeBox);

        toolbar.add(new JLabel("Model:"));
        modelBox = new JComboBox<>(new String[]{"BM25", "VSM"});
        toolbar.add(modelBox);

        expandBox = new JCheckBox("Expand");
        expandBox.setToolTipText("Rocchio pseudo-relevance feedback query expansion");
        toolbar.add(expandBox);

        searchBtn = new JButton("Search");
        searchBtn.setBackground(new Color(26, 95, 168));
        searchBtn.setForeground(Color.WHITE);
        searchBtn.setFocusPainted(false);
        searchBtn.addActionListener(e -> runSearch());
        toolbar.add(searchBtn);

        // ---- result list ----
        listModel   = new DefaultListModel<>();
        resultsList = new JList<>(listModel);
        resultsList.setCellRenderer(new ResultCellRenderer());
        resultsList.setFixedCellHeight(62);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showContent(resultsList.getSelectedIndex());
        });
        JScrollPane listScroll = new JScrollPane(resultsList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());

        // ---- content pane ----
        contentPane = new JEditorPane("text/html", "");
        contentPane.setEditable(false);
        contentPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JScrollPane contentScroll = new JScrollPane(contentPane);
        contentScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(210, 210, 210)), "Document"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, contentScroll);
        split.setDividerLocation(430);
        split.setResizeWeight(0.37);
        split.setBorder(BorderFactory.createEmptyBorder());

        // ---- status bar ----
        statusBar = new JLabel("  Ready — use \"quotes\" for phrase queries, check Expand for Rocchio expansion");
        statusBar.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        statusBar.setForeground(Color.GRAY);
        statusBar.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)),
            new EmptyBorder(4, 8, 4, 8)
        ));

        add(toolbar,   BorderLayout.NORTH);
        add(split,     BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
    }

    // ---- cell renderer ----

    private class ResultCellRenderer extends JPanel implements ListCellRenderer<QueryEvaluator.Result> {
        private final JLabel rankLabel  = new JLabel();
        private final JLabel nameLabel  = new JLabel();
        private final JLabel snipLabel  = new JLabel();
        private final JLabel scoreLabel = new JLabel();
        private final JLabel typeLabel  = new JLabel();
        private final Color  altRow     = new Color(248, 249, 252);

        ResultCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(8, 12, 8, 12));

            rankLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            rankLabel.setForeground(new Color(160, 160, 160));
            rankLabel.setPreferredSize(new Dimension(28, 46));

            JPanel center = new JPanel(new GridLayout(3, 1, 0, 1));
            center.setOpaque(false);
            nameLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            snipLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            snipLabel.setForeground(new Color(100, 100, 100));
            typeLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
            typeLabel.setForeground(new Color(130, 80, 180));
            center.add(nameLabel);
            center.add(snipLabel);
            center.add(typeLabel);

            scoreLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            scoreLabel.setForeground(new Color(26, 95, 168));
            scoreLabel.setPreferredSize(new Dimension(72, 46));
            scoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            add(rankLabel,  BorderLayout.WEST);
            add(center,     BorderLayout.CENTER);
            add(scoreLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends QueryEvaluator.Result> list,
                QueryEvaluator.Result value, int index,
                boolean isSelected, boolean cellHasFocus) {

            rankLabel.setText(String.valueOf(index + 1));
            nameLabel.setText("PMC" + value.pmcid);
            scoreLabel.setText(String.format("%.4f", value.score));

            // Plain-text snippet for the list (strip HTML tags)
            String raw = snippetCache.getOrDefault(value.pmcid, "");
            snipLabel.setText(raw.isEmpty() ? " " : raw.replaceAll("<[^>]+>", ""));

            // Type indicator
            String docType = SearchServer.detectType(value.path);
            typeLabel.setText(docType.isEmpty() ? "" : docType.toUpperCase());

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
                snipLabel.setForeground(list.getSelectionForeground());
                typeLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : altRow);
                nameLabel.setForeground(new Color(26, 95, 168));
                snipLabel.setForeground(new Color(100, 100, 100));
                typeLabel.setForeground(new Color(130, 80, 180));
            }
            setOpaque(true);
            return this;
        }
    }

    // ---- search logic ----

    private void runSearch() {
        String rawQuery = queryField.getText().trim();
        if (rawQuery.isEmpty()) return;

        searchBtn.setEnabled(false);
        listModel.clear();
        contentPane.setText("");
        snippetCache.clear();
        statusBar.setText("  Searching...");

        String selectedModel = ((String) modelBox.getSelectedItem()).toLowerCase();
        String selectedType  = (String) typeBox.getSelectedItem();
        boolean doExpand     = expandBox.isSelected();

        new SwingWorker<List<QueryEvaluator.Result>, Void>() {
            long   elapsed;
            List<String> expandedTerms = Collections.emptyList();
            Map<String, List<String>> oovInfo = Collections.emptyMap();

            @Override
            protected List<QueryEvaluator.Result> doInBackground() throws Exception {
                long t0 = System.currentTimeMillis();

                // Parse phrases + bag-of-words
                List<List<String>> phrases = new ArrayList<>();
                lastStems = QueryEvaluator.extractPhrases(rawQuery, phrases);

                // OOV info
                oovInfo = QueryEvaluator.getOovInfo(lastStems);

                // Optional Rocchio expansion
                if (doExpand && !lastStems.isEmpty()) {
                    List<String> expanded = QueryEvaluator.expandQueryRocchio(
                            lastStems,
                            QueryEvaluator.ROCCHIO_FEEDBACK_DOCS,
                            QueryEvaluator.ROCCHIO_EXPAND_TERMS);
                    expandedTerms = expanded.subList(lastStems.size(), expanded.size());
                    lastStems = expanded;
                }

                boolean filter = !"All".equals(selectedType);
                int fetchK = filter ? QueryEvaluator.TOP_K * 20 : QueryEvaluator.TOP_K * 3;

                List<QueryEvaluator.Result> results =
                        QueryEvaluator.runCombinedSearch(lastStems, phrases, selectedModel, fetchK);
                results = QueryEvaluator.filterByType(
                        results, filter ? selectedType.toLowerCase() : null, QueryEvaluator.TOP_K);

                // Pre-compute highlighted snippets
                List<String> allStems = new ArrayList<>(lastStems);
                for (List<String> ph : phrases) allStems.addAll(ph);
                for (QueryEvaluator.Result r : results)
                    snippetCache.put(r.pmcid, QueryEvaluator.getHighlightedSnippet(r.path, allStems));

                elapsed = System.currentTimeMillis() - t0;
                return results;
            }

            @Override
            protected void done() {
                try {
                    List<QueryEvaluator.Result> results = get();
                    for (QueryEvaluator.Result r : results) listModel.addElement(r);

                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(results.size()).append(" result(s)");
                    sb.append("  |  stems: ").append(lastStems);
                    if (!expandedTerms.isEmpty()) sb.append("  |  expanded: ").append(expandedTerms);
                    if (!oovInfo.isEmpty()) sb.append("  |  OOV: ").append(oovInfo.keySet());
                    sb.append("  |  ").append(elapsed).append("ms");
                    statusBar.setText(sb.toString());
                } catch (Exception ex) {
                    statusBar.setText("  Error: " + ex.getMessage());
                } finally {
                    searchBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    // ---- document content view ----

    private void showContent(int index) {
        if (index < 0 || index >= listModel.size()) return;
        QueryEvaluator.Result r = listModel.get(index);
        String snippet = snippetCache.getOrDefault(r.pmcid, "");
        String docType = SearchServer.detectType(r.path);

        try {
            NXMLFileReader reader = new NXMLFileReader(new File(r.path));
            String title   = esc(reader.getTitle());
            String abstr   = esc(reader.getAbstr());
            String journal = esc(reader.getJournal());
            String pub     = esc(reader.getPublisher());
            String authors = esc(String.valueOf(reader.getAuthors()));

            // Highlight query terms in abstract
            String highlightedAbstr = highlightTerms(abstr, lastStems);

            contentPane.setText(
                "<html><head><style>"
                + "body{font-family:Segoe UI,Arial,sans-serif;margin:14px;color:#1f2328}"
                + "mark{background:#fff59d;border-radius:2px;padding:0 1px}"
                + ".meta{color:#666;font-size:.85em;margin:0 0 10px 0}"
                + ".badge{display:inline-block;padding:1px 7px;border-radius:3px;font-size:.78em;"
                + "  font-weight:600;background:#f0f4ff;color:#3d5af1;margin-left:6px}"
                + "hr{border:none;border-top:1px solid #ddd;margin:10px 0}"
                + "</style></head><body>"
                + "<h2 style='color:#1a5fa8;margin:0 0 6px 0;'>" + title
                + (docType.isEmpty() ? "" : "<span class='badge'>" + docType.toUpperCase() + "</span>")
                + "</h2>"
                + "<p class='meta'><b>PMCID:</b> " + r.pmcid
                + " &nbsp;|&nbsp; <b>Score:</b> " + String.format("%.5f", r.score) + "</p>"
                + (snippet.isEmpty() ? "" : "<p style='background:#fffbea;padding:8px 10px;"
                    + "border-left:3px solid #fbbc04;border-radius:4px;font-size:.88em;"
                    + "margin-bottom:10px;'>" + snippet + "</p>")
                + "<hr>"
                + "<p style='line-height:1.6;'>" + highlightedAbstr + "</p>"
                + "<hr>"
                + "<p class='meta'><b>Journal:</b> " + journal + "<br>"
                + "<b>Publisher:</b> " + pub + "<br>"
                + "<b>Authors:</b> " + authors + "</p>"
                + "</body></html>"
            );
            contentPane.setCaretPosition(0);
        } catch (Exception ex) {
            contentPane.setText("<html><body><p style='color:red;padding:12px;'>"
                + "Could not load document: " + esc(ex.getMessage()) + "</p></body></html>");
        }
    }

    // Wrap matched terms in <mark> (operates on already HTML-escaped text)
    private String highlightTerms(String escapedText, List<String> stems) {
        if (escapedText == null || stems.isEmpty()) return escapedText == null ? "" : escapedText;
        String[] words = escapedText.split("((?<=\\s)|(?=\\s))");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String cleaned = w.toLowerCase().replaceAll("[^\\p{L}\\p{Nd}]", "");
            if (!cleaned.isEmpty() && stems.contains(mitos.stemmer.Stemmer.Stem(cleaned)))
                sb.append("<mark>").append(w).append("</mark>");
            else
                sb.append(w);
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) throws Exception {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        String base = QueryEvaluator.resolveBaseDir();
        QueryEvaluator.INDEX_DIR    = base + File.separator + "CollectionIndex";
        QueryEvaluator.STOPWORDS_EN = base + File.separator + "Stopwords" + File.separator + "stopwordsEn.txt";
        QueryEvaluator.STOPWORDS_GR = base + File.separator + "Stopwords" + File.separator + "stopwordsGr.txt";

        mitos.stemmer.Stemmer.Initialize();
        QueryEvaluator.loadStopwords(QueryEvaluator.STOPWORDS_EN);
        QueryEvaluator.loadStopwords(QueryEvaluator.STOPWORDS_GR);
        QueryEvaluator.loadVocabulary();
        QueryEvaluator.countDocuments();

        SwingUtilities.invokeLater(() -> new QueryEvaluatorGUI().setVisible(true));
    }
}
