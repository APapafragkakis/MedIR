package queryeval;

import gr.uoc.csd.hy463.NXMLFileReader;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

public class QueryEvaluatorGUI extends JFrame {

    private JTextField queryField;
    private JComboBox<String> typeBox;
    private JButton searchBtn;
    private DefaultListModel<QueryEvaluator.Result> listModel;
    private JList<QueryEvaluator.Result> resultsList;
    private JEditorPane contentPane;
    private JLabel statusBar;

    private List<String> lastStems = new ArrayList<>();
    private final Map<String, String> snippetCache = new HashMap<>();

    public QueryEvaluatorGUI() {
        super("MedIR");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 680);
        setLocationRelativeTo(null);
        buildUI();
    }

    private void buildUI() {
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
        searchPanel.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
            new EmptyBorder(2, 6, 2, 6)
        ));

        searchPanel.add(new JLabel("Query:"));
        queryField = new JTextField(40);
        queryField.addActionListener(e -> runSearch());
        searchPanel.add(queryField);

        searchPanel.add(new JLabel("Type:"));
        typeBox = new JComboBox<>(new String[]{"All", "Diagnosis", "Test", "Treatment"});
        searchPanel.add(typeBox);

        searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> runSearch());
        searchPanel.add(searchBtn);

        listModel = new DefaultListModel<>();
        resultsList = new JList<>(listModel);
        resultsList.setCellRenderer(new ResultCellRenderer());
        resultsList.setFixedCellHeight(58);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showContent(resultsList.getSelectedIndex());
        });
        JScrollPane listScroll = new JScrollPane(resultsList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());

        contentPane = new JEditorPane("text/html", "");
        contentPane.setEditable(false);
        contentPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JScrollPane contentScroll = new JScrollPane(contentPane);
        contentScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(210, 210, 210)), "Document"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, contentScroll);
        split.setDividerLocation(420);
        split.setResizeWeight(0.38);
        split.setBorder(BorderFactory.createEmptyBorder());

        statusBar = new JLabel("  Ready");
        statusBar.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        statusBar.setForeground(Color.GRAY);
        statusBar.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)),
            new EmptyBorder(4, 8, 4, 8)
        ));

        add(searchPanel, BorderLayout.NORTH);
        add(split,       BorderLayout.CENTER);
        add(statusBar,   BorderLayout.SOUTH);
    }

    private class ResultCellRenderer extends JPanel implements ListCellRenderer<QueryEvaluator.Result> {
        private final JLabel rankLabel  = new JLabel();
        private final JLabel nameLabel  = new JLabel();
        private final JLabel snipLabel  = new JLabel();
        private final JLabel scoreLabel = new JLabel();
        private final Color altRow = new Color(248, 249, 252);

        ResultCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(8, 12, 8, 12));

            rankLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            rankLabel.setForeground(new Color(160, 160, 160));
            rankLabel.setPreferredSize(new Dimension(28, 40));

            JPanel center = new JPanel(new GridLayout(2, 1, 0, 2));
            center.setOpaque(false);
            nameLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            snipLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            snipLabel.setForeground(new Color(110, 110, 110));
            center.add(nameLabel);
            center.add(snipLabel);

            scoreLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            scoreLabel.setForeground(new Color(60, 100, 200));
            scoreLabel.setPreferredSize(new Dimension(68, 40));
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
            nameLabel.setText(value.pmcid);
            scoreLabel.setText(String.format("%.4f", value.score));
            String snip = snippetCache.getOrDefault(value.pmcid, "");
            snipLabel.setText(snip.isEmpty() ? " " : snip);

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
                snipLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : altRow);
                nameLabel.setForeground(list.getForeground());
                snipLabel.setForeground(new Color(110, 110, 110));
            }
            setOpaque(true);
            return this;
        }
    }

    private void runSearch() {
        String query = queryField.getText().trim();
        if (query.isEmpty()) return;

        searchBtn.setEnabled(false);
        listModel.clear();
        contentPane.setText("");
        snippetCache.clear();
        statusBar.setText("  Searching...");

        new SwingWorker<List<QueryEvaluator.Result>, Void>() {
            long elapsed;

            @Override
            protected List<QueryEvaluator.Result> doInBackground() throws Exception {
                long t0 = System.currentTimeMillis();
                lastStems = QueryEvaluator.processQuery(query);
                String type = (String) typeBox.getSelectedItem();
                boolean filter = !"All".equals(type);
                int fetchK = filter ? QueryEvaluator.TOP_K * 20 : QueryEvaluator.TOP_K;
                List<QueryEvaluator.Result> results = QueryEvaluator.searchVSM(lastStems, fetchK);
                results = QueryEvaluator.filterByType(results, filter ? type : null, QueryEvaluator.TOP_K);
                for (QueryEvaluator.Result r : results)
                    snippetCache.put(r.pmcid, QueryEvaluator.getSnippet(r.path, lastStems));
                elapsed = System.currentTimeMillis() - t0;
                return results;
            }

            @Override
            protected void done() {
                try {
                    List<QueryEvaluator.Result> results = get();
                    for (QueryEvaluator.Result r : results) listModel.addElement(r);
                    statusBar.setText(String.format(
                        "  %d result(s)   |   stems: %s   |   %d ms",
                        results.size(), lastStems, elapsed));
                } catch (Exception ex) {
                    statusBar.setText("  Error: " + ex.getMessage());
                } finally {
                    searchBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void showContent(int index) {
        if (index < 0 || index >= listModel.size()) return;
        QueryEvaluator.Result r = listModel.get(index);
        try {
            NXMLFileReader reader = new NXMLFileReader(new File(r.path));
            String title   = esc(reader.getTitle());
            String abstr   = esc(reader.getAbstr());
            String journal = esc(reader.getJournal());
            String pub     = esc(reader.getPublisher());
            String authors = esc(String.valueOf(reader.getAuthors()));

            contentPane.setText(
                "<html><body style='font-family:Segoe UI,Arial,sans-serif;margin:14px;'>"
                + "<h2 style='color:#1a5fa8;margin:0 0 6px 0;'>" + title + "</h2>"
                + "<p style='color:#666;font-size:0.85em;margin:0 0 10px 0;'>"
                + "<b>PMCID:</b> " + r.pmcid + " &nbsp;|&nbsp; "
                + "<b>Score:</b> " + String.format("%.5f", r.score) + "</p>"
                + "<hr style='border:none;border-top:1px solid #ddd;margin-bottom:12px;'>"
                + "<p style='line-height:1.6;'>" + abstr + "</p>"
                + "<hr style='border:none;border-top:1px solid #eee;margin:12px 0;'>"
                + "<p style='font-size:0.85em;color:#555;line-height:1.8;'>"
                + "<b>Journal:</b> " + journal + "<br>"
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
