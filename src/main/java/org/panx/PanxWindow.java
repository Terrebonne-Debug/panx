package org.panx;

import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** Swing owns view state; network, MIME and large cache queries run on workers. */
public final class PanxWindow extends JFrame {
  private final Store store;
  private final NewsService news;
  private final DownloadQueue downloads;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final JComboBox<Server> servers = new JComboBox<>();
  private final JList<NntpClient.Group> groups = new JList<>(new GroupListModel(List.of()));
  private final JTextField groupSearch = new JTextField(), articleSearch = new JTextField();
  private final JCheckBox subscribed = new JCheckBox("Subscribed"),
      unread = new JCheckBox("Unread"),
      flagged = new JCheckBox("Flagged"),
      threaded = new JCheckBox("Threaded", true),
      killed = new JCheckBox("Hide killed", true),
      offline = new JCheckBox("Offline");
  private final JSpinner batch = new JSpinner(new SpinnerNumberModel(5000, 1, 100000, 1000));
  private final ArticleModel articleModel = new ArticleModel();
  private final JTable articles = new JTable(articleModel);
  private final JTextArea body = new JTextArea(), rawText = new JTextArea();
  private final DefaultListModel<Mime.Attachment> attachmentModel = new DefaultListModel<>();
  private final JList<Mime.Attachment> attachments = new JList<>(attachmentModel);
  private final JLabel messageTitle = new JLabel("Select an article"), status = new JLabel("Ready");
  private final DefaultTableModel jobModel =
      tableModel("ID", "File", "State", "Progress / destination");
  private final JTable jobs = new JTable(jobModel);
  private final javax.swing.Timer jobTimer;
  private List<Store.Job> jobRows = List.of();
  private String currentRaw;
  private long groupGeneration, listGeneration, articleGeneration;
  private boolean closing, loadingServers, loadingGroups, jobsLoading;
  private Server displayedGroupsServer;
  private int pending;

  public PanxWindow(Store store, boolean startOffline) throws Exception {
    super("Panx — Usenet reader & poster");
    this.store = store;
    news = new NewsService(store);
    downloads = new DownloadQueue(store, news);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setMinimumSize(new Dimension(940, 650));
    setSize(1280, 860);
    setLocationByPlatform(true);
    offline.setSelected(startOffline);
    setJMenuBar(menus());
    setLayout(new BorderLayout(6, 6));
    var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    toolbar.add(new JLabel("Server"));
    servers.setPreferredSize(new Dimension(190, 28));
    toolbar.add(servers);
    toolbar.add(button("Get groups", () -> refreshGroups()));
    toolbar.add(button("Get headers", () -> fetchHeaders(false)));
    toolbar.add(button("Older", () -> fetchHeaders(true)));
    toolbar.add(new JLabel("Batch"));
    toolbar.add(batch);
    toolbar.add(button("New post", () -> compose(null, null)));
    toolbar.add(button("Reply", this::reply));
    toolbar.add(offline);
    add(toolbar, BorderLayout.NORTH);

    var groupPanel = new JPanel(new BorderLayout(4, 4));
    groupPanel.setBorder(BorderFactory.createTitledBorder("Newsgroups"));
    var groupTools = new JPanel(new BorderLayout(3, 3));
    groupSearch.setToolTipText("Filter cached groups by name");
    groupTools.add(groupSearch, BorderLayout.NORTH);
    groupTools.add(subscribed, BorderLayout.SOUTH);
    groupPanel.add(groupTools, BorderLayout.NORTH);
    groupPanel.add(new JScrollPane(groups));
    var groupButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    groupButtons.add(button("Subscribe / remove", this::toggleSubscription));
    groupButtons.add(button("Go to…", this::goToGroup));
    groupPanel.add(groupButtons, BorderLayout.SOUTH);
    groups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    groups.setFixedCellHeight(groups.getFontMetrics(groups.getFont()).getHeight() + 6);
    groups.setFixedCellWidth(250);
    groups.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof NntpClient.Group g) setText(groupLabel(g));
            return this;
          }
        });

    var articlePanel = new JPanel(new BorderLayout(4, 4));
    var filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    articleSearch.setColumns(20);
    filters.add(new JLabel("Search"));
    filters.add(articleSearch);
    for (var c : List.of(unread, flagged, threaded, killed)) filters.add(c);
    articlePanel.add(filters, BorderLayout.NORTH);
    articlePanel.add(new JScrollPane(articles));
    articles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    articles.setRowHeight(25);
    articles.setFillsViewportHeight(true);
    articles.getColumnModel().getColumn(0).setPreferredWidth(45);
    articles.getColumnModel().getColumn(1).setPreferredWidth(420);
    articles.getColumnModel().getColumn(2).setPreferredWidth(220);
    var renderer =
        new DefaultTableCellRenderer() {
          @Override
          public Component getTableCellRendererComponent(
              JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            if (row < articleModel.rows.size())
              setFont(
                  t.getFont()
                      .deriveFont(
                          articleModel.rows.get(row).article().read() ? Font.PLAIN : Font.BOLD));
            return this;
          }
        };
    articles.setDefaultRenderer(Object.class, renderer);
    var articleActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
    articleActions.add(button("Next unread", this::nextUnread));
    articleActions.add(button("Read / unread", this::toggleRead));
    articleActions.add(button("Flag", this::toggleFlag));
    articleActions.add(button("Catch up", this::catchUp));
    articleActions.add(button("Download", this::queueSelected));
    articleActions.add(button("Export NZB", this::exportNzb));
    articlePanel.add(articleActions, BorderLayout.SOUTH);

    body.setEditable(false);
    body.setLineWrap(true);
    body.setWrapStyleWord(true);
    body.setMargin(new Insets(12, 12, 12, 12));
    body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    rawText.setEditable(false);
    var readerTabs = new JTabbedPane();
    readerTabs.addTab("Message", new JScrollPane(body));
    readerTabs.addTab("Source", new JScrollPane(rawText));
    var attachmentPanel = new JPanel(new BorderLayout());
    attachmentPanel.add(new JScrollPane(attachments));
    attachmentPanel.add(
        button("Save selected attachments…", this::saveAttachments), BorderLayout.SOUTH);
    readerTabs.addTab("Attachments", attachmentPanel);
    var reader = new JPanel(new BorderLayout(3, 3));
    messageTitle.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    reader.add(messageTitle, BorderLayout.NORTH);
    reader.add(readerTabs);
    var vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, articlePanel, reader);
    vertical.setResizeWeight(.52);
    vertical.setDividerLocation(320);
    var main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, groupPanel, vertical);
    main.setResizeWeight(.23);
    main.setDividerLocation(285);

    var jobPanel = new JPanel(new BorderLayout());
    jobPanel.add(new JScrollPane(jobs));
    var jobButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    jobButtons.add(button("Resume", this::resumeJobs));
    jobButtons.add(button("Pause", this::pauseJobs));
    jobButtons.add(button("Import NZB…", this::importNzb));
    jobPanel.add(jobButtons, BorderLayout.SOUTH);
    var tabs = new JTabbedPane();
    tabs.addTab("Reader", main);
    tabs.addTab("Downloads", jobPanel);
    add(tabs);
    status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    add(status, BorderLayout.SOUTH);

    servers.addActionListener(e -> {
      if (!loadingServers) {
        resetReader();
        loadGroups();
      }
    });
    groups.addListSelectionListener(e -> {
      if (!loadingGroups && !e.getValueIsAdjusting()) {
        resetReader();
        loadArticles(null);
      }
    });
    articles
        .getSelectionModel()
        .addListSelectionListener(e -> {
          if (!e.getValueIsAdjusting()) openArticle();
        });
    debounce(groupSearch, () -> groupGeneration++, this::loadGroups);
    debounce(articleSearch, () -> loadArticles(null));
    subscribed.addActionListener(e -> loadGroups());
    for (var c : List.of(unread, flagged, threaded, killed))
      c.addActionListener(e -> loadArticles(null));
    key("N", this::nextUnread);
    key("R", this::reply);
    key("F", this::toggleFlag);
    key("U", this::toggleRead);
    key("control ENTER", () -> fetchHeaders(false));
    key("control N", () -> compose(null, null));
    key("control F", () -> articleSearch.requestFocusInWindow());
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            shutdown();
          }
        });
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            // Re-layout and repaint the complete client area, including newly exposed pixels.
            validate();
            getRootPane().repaint();
          }
        });
    jobTimer = new javax.swing.Timer(1000, e -> loadJobs());
    jobTimer.start();
    reloadServers(null);
    loadJobs();
  }

  private static JButton button(String title, Runnable action) {
    var b = new JButton(title);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static void item(JMenu menu, String title, Runnable action) {
    var i = new JMenuItem(title);
    i.addActionListener(e -> action.run());
    menu.add(i);
  }

  private static DefaultTableModel tableModel(String... columns) {
    return new DefaultTableModel(columns, 0) {
      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private static void debounce(JTextField input, Runnable action) {
    debounce(input, () -> {}, action);
  }

  private static void debounce(JTextField input, Runnable invalidate, Runnable action) {
    var timer = new javax.swing.Timer(220, e -> action.run());
    timer.setRepeats(false);
    input
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                invalidate.run();
                timer.restart();
              }

              public void removeUpdate(DocumentEvent e) {
                invalidate.run();
                timer.restart();
              }

              public void changedUpdate(DocumentEvent e) {
                invalidate.run();
                timer.restart();
              }
            });
  }

  private static String groupLabel(NntpClient.Group group) {
    return (group.subscribed() ? "★ " : "  ") + group.name() + " (" + group.count() + ")";
  }

  private static JPanel form(Object... fields) {
    var panel = new JPanel(new GridLayout(0, 2, 8, 8));
    for (int i = 0; i < fields.length; i += 2) {
      panel.add(new JLabel((String) fields[i]));
      panel.add((Component) fields[i + 1]);
    }
    return panel;
  }

  private JMenuBar menus() {
    var bar = new JMenuBar();
    JMenu file = new JMenu("File"),
        server = new JMenu("Servers"),
        tools = new JMenu("Tools"),
        help = new JMenu("Help");
    item(file, "New post…", () -> compose(null, null));
    item(file, "Saved drafts…", this::showDrafts);
    item(file, "Import NZB…", this::importNzb);
    item(file, "Save article source…", this::saveSource);
    item(file, "Quit", this::shutdown);
    item(server, "Add server…", () -> editServer(false));
    item(server, "Edit selected server…", () -> editServer(true));
    item(tools, "Scoring / kill rules…", this::rules);
    item(tools, "Identity and signature…", this::identity);
    item(
        tools,
        "Clear cached bodies",
        () -> {
          if (JOptionPane.showConfirmDialog(
                  this,
                  "Delete cached article bodies? Headers and read flags are retained.",
                  "Clear cache",
                  JOptionPane.OK_CANCEL_OPTION)
              == JOptionPane.OK_OPTION)
            task(
                "Clearing cache",
                () -> {
                  store.clearBodies();
                  return true;
                },
                v -> status.setText("Body cache cleared"));
        });
    item(
        help,
        "About Panx",
        () ->
            JOptionPane.showMessageDialog(
                this,
                "Panx 1.0.0\nJava Usenet reader and poster\n\nN: next unread · R: reply · F: flag · U: read/unread\nCtrl+N: compose · Ctrl+F: search · Ctrl+Enter: headers\n\nCache: "
                    + store.directory));
    bar.add(file);
    bar.add(server);
    bar.add(tools);
    bar.add(help);
    return bar;
  }

  private void key(String stroke, Runnable action) {
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(stroke), stroke);
    getRootPane()
        .getActionMap()
        .put(
            stroke,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                if (stroke.length() == 1
                    && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        instanceof javax.swing.text.JTextComponent) return;
                action.run();
              }
            });
  }

  private <T> void task(String label, Work<T> work, Consumer<T> done) {
    task(label, work, done, this::error);
  }

  private <T> void task(String label, Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
    if (closing) return;
    pending++;
    status.setText(label + "…");
    workers.submit(() -> {
      try {
        T value = work.run();
        SwingUtilities.invokeLater(() -> {
          if (!closing) {
            status.setText(label + " complete");
            done.accept(value);
          }
        });
      } catch (Exception e) {
        SwingUtilities.invokeLater(() -> {
          if (!closing) failed.accept(e);
        });
      } finally {
        SwingUtilities.invokeLater(() -> pending--);
      }
    });
  }

  private void error(Exception e) {
    Throwable cause = e;
    while (cause instanceof ExecutionException && cause.getCause() != null)
      cause = cause.getCause();
    String text = Objects.requireNonNullElse(cause.getMessage(), cause.toString());
    status.setText(text);
    JOptionPane.showMessageDialog(this, text, "Panx", JOptionPane.ERROR_MESSAGE);
  }

  private Server server() {
    return (Server) servers.getSelectedItem();
  }

  private String group() {
    return groups.getSelectedValue() == null ? null : groups.getSelectedValue().name();
  }

  private boolean online(Server s) {
    if (s == null) return false;
    if (offline.isSelected()) {
      status.setText("Offline: use cached articles, or turn off Offline");
      return false;
    }
    return true;
  }

  private void reloadServers(Long select) {
    record Profiles(List<Server> list, Server startup) {}
    task(
        "Loading servers",
        () -> new Profiles(store.servers(), store.defaultServer()),
        profiles -> {
          loadingServers = true;
          servers.removeAllItems();
          Long selected =
              select != null ? select : profiles.startup() == null ? null : profiles.startup().id();
          for (var s : profiles.list()) {
            servers.addItem(s);
            if (selected != null && s.id() == selected) servers.setSelectedItem(s);
          }
          loadingServers = false;
          resetReader();
          loadGroups();
          if (profiles.list().isEmpty())
            status.setText("Add a news server using Servers → Add server.");
        });
  }

  private void resetReader() {
    listGeneration++;
    articleGeneration++;
    articleModel.setRows(List.of());
    body.setText("");
    rawText.setText("");
    currentRaw = null;
    attachmentModel.clear();
    messageTitle.setText("Select an article");
  }

  private void loadGroups() {
    Server s = server();
    if (s == null) return;
    long generation = ++groupGeneration;
    String query = groupSearch.getText();
    boolean only = subscribed.isSelected();
    FontMetrics metrics = groups.getFontMetrics(groups.getFont());
    record Results(GroupListModel model, int width) {}
    task(
        "Loading groups",
        () -> {
          var model = new GroupListModel(store.groups(s.id(), query, only));
          int width = 1;
          for (var g : model.rows) width = Math.max(width, metrics.stringWidth(groupLabel(g)) + 16);
          return new Results(model, width);
        },
        result -> {
          if (generation != groupGeneration || server() != s) return;
          String selected = group();
          boolean sameServer = displayedGroupsServer == s;
          loadingGroups = true;
          try {
            groups.setFixedCellWidth(result.width());
            groups.setModel(result.model());
            int index = -1;
            for (int i = 0; i < result.model().getSize(); i++)
              if (result.model().getElementAt(i).name().equals(selected)) {
                index = i;
                break;
              }
            if (index < 0 && result.model().getSize() > 0) index = 0;
            groups.setSelectedIndex(index);
            displayedGroupsServer = s;
          } finally {
            loadingGroups = false;
          }
          if (!sameServer || !Objects.equals(selected, group())) {
            resetReader();
            loadArticles(null);
          }
          status.setText(
              result.model().getSize() + " cached groups (up to 10,000; search to narrow)");
        },
        failure -> {
          if (generation == groupGeneration && server() == s) error(failure);
        });
  }

  private void loadArticles(String preserveId) {
    Server s = server();
    String g = group();
    if (s == null || g == null) return;
    long generation = ++listGeneration;
    String query = articleSearch.getText();
    boolean u = unread.isSelected(),
        f = flagged.isSelected(),
        t = threaded.isSelected(),
        k = killed.isSelected();
    task(
        "Loading articles",
        () -> Threads.build(store.articles(s.id(), g, query, u, f, 100000), store.rules(), t, k),
        rows -> {
          if (generation != listGeneration || server() != s || !g.equals(group())) return;
          articleModel.setRows(rows);
          status.setText(rows.size() + " articles in " + g);
          if (preserveId != null)
            for (int i = 0; i < rows.size(); i++)
              if (rows.get(i).article().messageId().equals(preserveId)) {
                articles.setRowSelectionInterval(i, i);
                break;
              }
        });
  }

  private void refreshGroups() {
    Server s = server();
    if (online(s))
      task(
          "Fetching group list",
          () -> news.refreshGroups(s),
          count -> {
            if (server() == s) loadGroups();
          });
  }

  private void fetchHeaders(boolean older) {
    Server s = server();
    String g = group();
    if (g != null && online(s)) {
      int count = (Integer) batch.getValue();
      task(
          "Fetching " + (older ? "older " : "") + "headers",
          () -> news.headers(s, g, count, older),
          n -> {
            if (server() == s && g.equals(group())) loadArticles(null);
          });
    }
  }

  private void toggleSubscription() {
    Server s = server();
    var g = groups.getSelectedValue();
    if (s != null && g != null)
      task(
          "Saving subscription",
          () -> {
            store.subscribe(s.id(), g.name(), !g.subscribed());
            return true;
          },
          v -> loadGroups());
  }

  private void goToGroup() {
    Server s = server();
    if (s == null) return;
    String name = JOptionPane.showInputDialog(this, "Newsgroup name");
    if (name == null) return;
    task(
        "Adding group",
        () -> {
          store.subscribe(s.id(), name.strip(), true);
          return true;
        },
        v -> {
          groupSearch.setText(name.strip());
          subscribed.setSelected(false);
          loadGroups();
        });
  }

  private List<Article> selectedArticles() {
    var selected = new ArrayList<Article>();
    for (int i : articles.getSelectedRows())
      if (i < articleModel.rows.size()) selected.add(articleModel.rows.get(i).article());
    return List.copyOf(selected);
  }

  private Article selectedArticle() {
    var selected = selectedArticles();
    return selected.isEmpty() ? null : selected.getFirst();
  }

  private void openArticle() {
    Article a = selectedArticle();
    long generation = ++articleGeneration;
    currentRaw = null;
    attachmentModel.clear();
    body.setText("");
    rawText.setText("");
    if (a == null) {
      messageTitle.setText("Select an article");
      return;
    }
    Server s = server();
    String g = group();
    boolean cachedOnly = offline.isSelected();
    messageTitle.setText(a.subject());
    body.setText("Loading…");
    record Loaded(String raw, Mime.Decoded decoded) {}
    task(
        "Opening article",
        () -> {
          String raw = news.article(s, a.messageId(), cachedOnly);
          return new Loaded(raw, Mime.decode(raw));
        },
        loaded -> {
          if (generation != articleGeneration) return;
          currentRaw = loaded.raw();
          body.setText(
              "From: "
                  + a.from()
                  + "\nDate: "
                  + a.date()
                  + "\nSubject: "
                  + a.subject()
                  + "\n\n"
                  + loaded.decoded().text());
          body.setCaretPosition(0);
          rawText.setText(loaded.raw());
          rawText.setCaretPosition(0);
          for (var attachment : loaded.decoded().attachments())
            attachmentModel.addElement(attachment);
          task(
              "Marking read",
              () -> {
                store.mark(s.id(), g, a.messageId(), true, a.flagged());
                return true;
              },
              v -> articleModel.mark(a.messageId(), true, a.flagged()));
        },
        failure -> {
          if (generation != articleGeneration) return;
          if (failure instanceof NntpClient.ProtocolException protocol
              && (protocol.code == 430 || protocol.code == 423)) {
            status.setText("Article unavailable on " + s.name());
            body.setText(
                "This article is unavailable on "
                    + s.name()
                    + ".\n\n"
                    + "A header can still be listed or cached even when the provider cannot supply the article body. "
                    + "It may have expired, been removed, or be temporarily unavailable.\n\n"
                    + "Select the article again to retry. A header refresh cannot restore a body that the provider no longer has.\n\n"
                    + "Server response: "
                    + protocol.getMessage());
          } else {
            String detail = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
            status.setText("Unable to open article: " + detail);
            body.setText("Unable to open this article.\n\n" + detail);
          }
          body.setCaretPosition(0);
        });
  }

  private void toggleRead() {
    markSelected(false);
  }

  private void toggleFlag() {
    markSelected(true);
  }

  private void markSelected(boolean flag) {
    Server s = server();
    String g = group();
    var selected = selectedArticles();
    if (s == null || g == null || selected.isEmpty()) return;
    task(
        "Updating articles",
        () -> {
          for (var a : selected)
            store.mark(
                s.id(),
                g,
                a.messageId(),
                flag ? a.read() : !a.read(),
                flag ? !a.flagged() : a.flagged());
          return true;
        },
        v -> loadArticles(null));
  }

  private void catchUp() {
    Server s = server();
    String g = group();
    if (s != null && g != null)
      task(
          "Marking group read",
          () -> {
            store.markGroup(s.id(), g);
            return true;
          },
          v -> loadArticles(null));
  }

  private void nextUnread() {
    int start = Math.max(0, articles.getSelectedRow() + 1);
    for (int n = 0; n < articleModel.rows.size(); n++) {
      int i = (start + n) % articleModel.rows.size();
      if (!articleModel.rows.get(i).article().read()) {
        articles.setRowSelectionInterval(i, i);
        articles.scrollRectToVisible(articles.getCellRect(i, 0, true));
        return;
      }
    }
    status.setText("No unread articles in this view");
  }

  private Path chooseDirectory() {
    var chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    return chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION
        ? chooser.getSelectedFile().toPath()
        : null;
  }

  private Path chooseFile(boolean save, String name) {
    var chooser = new JFileChooser();
    if (name != null) chooser.setSelectedFile(new java.io.File(name));
    return (save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this))
            == JFileChooser.APPROVE_OPTION
        ? chooser.getSelectedFile().toPath()
        : null;
  }

  private void saveAttachments() {
    var selected = attachments.getSelectedValuesList();
    if (selected.isEmpty()) return;
    Path dir = chooseDirectory();
    if (dir != null)
      task(
          "Saving attachments",
          () -> {
            for (var a : selected) Mime.saveAttachment(dir, a);
            return selected.size();
          },
          n -> status.setText("Saved " + n + " attachment(s)"));
  }

  private void saveSource() {
    String raw = currentRaw;
    if (raw == null) return;
    Path path = chooseFile(true, "article.eml");
    if (path != null)
      task(
          "Saving article",
          () ->
              Files.writeString(
                  path, raw, StandardCharsets.ISO_8859_1, StandardOpenOption.CREATE_NEW),
          p -> status.setText("Saved " + p));
  }

  private void exportNzb() {
    var selected = selectedArticles();
    String g = group();
    if (selected.isEmpty() || g == null) return;
    Path path = chooseFile(true, "articles.nzb");
    if (path != null)
      task(
          "Exporting NZB",
          () -> {
            Nzb.write(path, g, selected);
            return path;
          },
          p -> status.setText("Saved " + p));
  }

  private void queueSelected() {
    Server s = server();
    var selected = selectedArticles();
    if (s == null || selected.isEmpty()) return;
    Path dir = chooseDirectory();
    if (dir == null) return;
    boolean start = !offline.isSelected();
    task(
        "Queueing downloads",
        () -> {
          for (var a : selected) {
            long id = downloads.add(s, a.subject(), List.of(a.messageId()), dir);
            if (start) downloads.start(id);
          }
          return true;
        },
        v -> loadJobs());
  }

  private void importNzb() {
    Server s = server();
    if (s == null) return;
    Path path = chooseFile(false, null);
    if (path == null) return;
    Path dir = chooseDirectory();
    if (dir == null) return;
    boolean start = !offline.isSelected();
    task(
        "Importing NZB",
        () -> {
          var entries = Nzb.read(path);
          for (var f : entries) {
            long id = downloads.add(s, f.subject(), f.ids(), dir);
            if (start) downloads.start(id);
          }
          return entries.size();
        },
        n -> {
          status.setText("Imported " + n + " file(s)");
          loadJobs();
        });
  }

  private void loadJobs() {
    if (closing || jobsLoading) return;
    jobsLoading = true;
    workers.submit(() -> {
      try {
        var list = store.jobs();
        SwingUtilities.invokeLater(() -> {
          jobsLoading = false;
          if (closing) return;
          var selected = selectedJobIds();
          jobRows = list;
          jobModel.setRowCount(0);
          for (var j : list)
            jobModel.addRow(new Object[] {j.id(), j.name(), j.state(), j.detail()});
          for (int i = 0; i < list.size(); i++)
            if (selected.contains(list.get(i).id())) jobs.addRowSelectionInterval(i, i);
        });
      } catch (Exception e) {
        SwingUtilities.invokeLater(() -> {
          jobsLoading = false;
          if (!closing) status.setText(e.getMessage());
        });
      }
    });
  }

  private Set<Long> selectedJobIds() {
    Set<Long> ids = new HashSet<>();
    for (int i : jobs.getSelectedRows()) if (i < jobRows.size()) ids.add(jobRows.get(i).id());
    return ids;
  }

  private void resumeJobs() {
    if (offline.isSelected()) {
      status.setText("Turn off Offline to resume downloads");
      return;
    }
    var ids = selectedJobIds();
    task(
        "Resuming downloads",
        () -> {
          for (long id : ids) downloads.start(id);
          return true;
        },
        v -> loadJobs());
  }

  private void pauseJobs() {
    var ids = selectedJobIds();
    task(
        "Pausing downloads",
        () -> {
          for (long id : ids) downloads.pause(id);
          return true;
        },
        v -> loadJobs());
  }

  private void editServer(boolean edit) {
    Server old = edit ? server() : null;
    if (edit && old == null) return;
    var name = new JTextField(old == null ? "My news server" : old.name());
    var host = new JTextField(old == null ? "" : old.host());
    var port = new JSpinner(new SpinnerNumberModel(old == null ? 563 : old.port(), 1, 65535, 1));
    var tls = new JCheckBox("TLS (verify certificate and hostname)", old == null || old.tls());
    var user = new JTextField(old == null ? "" : old.username());
    var pass = new JPasswordField(old == null ? "" : old.password());
    var connections =
        new JSpinner(new SpinnerNumberModel(old == null ? 40 : old.connections(), 1, 40, 1));
    var remember = new JCheckBox("Remember password on disk (stored as plain text)");
    var startupDefault = new JCheckBox("Use by default at startup");
    try {
      startupDefault.setSelected(old != null && store.isDefaultServer(old.id()));
      remember.setSelected(old != null && store.remembersPassword(old.id()));
    } catch (Exception e) {
      error(e);
      return;
    }
    Work<Server> enteredSettings =
        () -> {
          port.commitEdit();
          connections.commitEdit();
          return new Server(
              old == null ? 0 : old.id(),
              name.getText(),
              host.getText(),
              (Integer) port.getValue(),
              tls.isSelected(),
              user.getText(),
              new String(pass.getPassword()),
              (Integer) connections.getValue());
        };
    var connectionTest = new ConnectionTestPanel(enteredSettings::run);
    var changed =
        new DocumentListener() {
          public void insertUpdate(DocumentEvent e) {
            connectionTest.settingsChanged();
          }

          public void removeUpdate(DocumentEvent e) {
            connectionTest.settingsChanged();
          }

          public void changedUpdate(DocumentEvent e) {
            connectionTest.settingsChanged();
          }
        };
    for (var field : List.of(name, host, user, pass))
      field.getDocument().addDocumentListener(changed);
    port.addChangeListener(e -> connectionTest.settingsChanged());
    connections.addChangeListener(e -> connectionTest.settingsChanged());
    tls.addActionListener(e -> connectionTest.settingsChanged());
    var fields =
        form(
            "Name",
            name,
            "Host",
            host,
            "Port",
            port,
            "",
            tls,
            "Username",
            user,
            "Password",
            pass,
            "Connections",
            connections,
            "",
            remember,
            "",
            startupDefault);
    var panel = new JPanel(new BorderLayout(8, 12));
    panel.add(fields, BorderLayout.CENTER);
    panel.add(connectionTest, BorderLayout.SOUTH);
    while (JOptionPane.showConfirmDialog(
            this,
            panel,
            edit ? "Edit server" : "Add server",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        var value = enteredSettings.run();
        boolean savePassword = remember.isSelected();
        boolean useByDefault = startupDefault.isSelected();
        task(
            "Saving server",
            () -> {
              Server saved = store.saveServer(value, savePassword, useByDefault);
              news.configureServer(saved);
              return saved;
            },
            saved -> reloadServers(saved.id()));
        return;
      } catch (Exception e) {
        error(e);
      }
    }
  }

  private void identity() {
    task(
        "Loading identity",
        () -> List.of(store.setting("from", ""), store.setting("signature", "")),
        values -> {
          var from = new JTextField(values.get(0), 30);
          var sig = new JTextArea(values.get(1), 4, 30);
          if (JOptionPane.showConfirmDialog(
                  this,
                  form("From (Name <email>)", from, "Signature", new JScrollPane(sig)),
                  "Posting identity",
                  JOptionPane.OK_CANCEL_OPTION)
              == JOptionPane.OK_OPTION) {
            String identity = from.getText(), signature = sig.getText();
            task(
                "Saving identity",
                () -> {
                  store.saveSetting("from", identity);
                  store.saveSetting("signature", signature);
                  return true;
                },
                v -> {});
          }
        });
  }

  private void reply() {
    Article a = selectedArticle();
    if (a != null && currentRaw != null) compose(a, currentRaw);
  }

  private void compose(Article reply, String raw) {
    Server s = server();
    if (s == null) return;
    String g = group();
    task(
        "Preparing composer",
        () -> List.of(store.setting("from", ""), store.setting("signature", "")),
        identity -> {
          try {
            String text = "", subject = "", refs = "", to = Objects.requireNonNullElse(g, "");
            if (reply != null) {
              subject =
                  reply.subject().matches("(?is)^re:.*")
                      ? reply.subject()
                      : "Re: " + reply.subject();
              refs = (reply.references() + " " + reply.messageId()).strip();
              String quoted = Mime.decode(raw).text();
              text =
                  "On "
                      + reply.date()
                      + ", "
                      + reply.from()
                      + " wrote:\n> "
                      + quoted.replace("\n", "\n> ")
                      + "\n\n";
            }
            if (!identity.get(1).isBlank()) text += "\n-- \n" + identity.get(1);
            new Composer(s, identity.get(0), to, subject, text, refs).setVisible(true);
          } catch (Exception e) {
            error(e);
          }
        });
  }

  private void showDrafts() {
    task(
        "Loading drafts",
        store::drafts,
        drafts -> {
          if (drafts.isEmpty()) {
            status.setText("No saved drafts");
            return;
          }
          var options = new LinkedHashMap<String, String>();
          for (var draft : drafts)
            try {
              options.put(
                  "Draft " + draft[0] + ": " + Mime.message(draft[1]).getSubject(), draft[1]);
            } catch (Exception e) {
              error(e);
            }
          Object chosen =
              JOptionPane.showInputDialog(
                  this,
                  "Choose draft",
                  "Drafts",
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  options.keySet().toArray(),
                  null);
          if (chosen != null && server() != null)
            try {
              String raw = options.get(chosen.toString());
              var msg = Mime.message(raw);
              var decoded = Mime.decode(raw);
              var composer =
                  new Composer(
                      server(),
                      msg.getHeader("From", ", "),
                      msg.getHeader("Newsgroups", ","),
                      msg.getSubject(),
                      decoded.text(),
                      Objects.requireNonNullElse(msg.getHeader("References", " "), ""));
              composer.embedded.addAll(decoded.attachments());
              for (var a : decoded.attachments()) composer.fileNames.addElement(a.toString());
              composer.setVisible(true);
            } catch (Exception e) {
              error(e);
            }
        });
  }

  private void rules() {
    task(
        "Loading rules",
        store::rules,
        list -> {
          var model = tableModel("ID", "Field", "Regular expression", "Score");
          for (var r : list)
            model.addRow(new Object[] {r.id(), r.field(), r.expression(), r.score()});
          var table = new JTable(model);
          var dialog = new JDialog(this, "Scoring and kill rules", false);
          var panel = new JPanel(new BorderLayout(4, 4));
          panel.add(new JScrollPane(table));
          var tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
          tools.add(
              button(
                  "Add rule…",
                  () -> {
                    var field = new JComboBox<>(new String[] {"subject", "from"});
                    var pattern = new JTextField(25);
                    var score = new JSpinner(new SpinnerNumberModel(-999, -100000, 100000, 1));
                    if (JOptionPane.showConfirmDialog(
                            dialog,
                            form("Field", field, "Regex", pattern, "Score (-999 = kill)", score),
                            "Add rule",
                            JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION) {
                      String f = (String) field.getSelectedItem(), p = pattern.getText();
                      int value = (Integer) score.getValue();
                      task(
                          "Saving rule",
                          () -> {
                            store.rule(f, p, value);
                            return true;
                          },
                          v -> {
                            dialog.dispose();
                            rules();
                            loadArticles(null);
                          });
                    }
                  }));
          tools.add(
              button(
                  "Delete selected",
                  () -> {
                    int i = table.getSelectedRow();
                    if (i >= 0) {
                      long id = list.get(i).id();
                      task(
                          "Deleting rule",
                          () -> {
                            store.deleteRule(id);
                            return true;
                          },
                          v -> {
                            dialog.dispose();
                            rules();
                            loadArticles(null);
                          });
                    }
                  }));
          panel.add(tools, BorderLayout.SOUTH);
          dialog.add(panel);
          dialog.setSize(680, 360);
          dialog.setLocationRelativeTo(this);
          dialog.setVisible(true);
        });
  }

  public void shutdown() {
    if (closing) return;
    if ((downloads.active()
            || pending > 0
            || Arrays.stream(getOwnedWindows()).anyMatch(Window::isVisible))
        && JOptionPane.showConfirmDialog(
                this,
                "Close Panx? Active work will stop; downloads can be resumed. Save any open drafts first.",
                "Quit",
                JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION) return;
    closeResources();
  }

  /** Also used by the desktop integration test, after its asynchronous work has completed. */
  void closeResources() {
    if (closing) return;
    closing = true;
    jobTimer.stop();
    dispose();
    Thread.ofPlatform()
        .name("panx-shutdown")
        .start(() -> {
          news.close();
          downloads.close();
          workers.shutdownNow();
          try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
            store.close();
          } catch (Exception e) {
            System.err.println("Panx close: " + e.getMessage());
          }
        });
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws Exception;
  }

  /** Immutable snapshots avoid thousands of list events and repeated layout on the UI thread. */
  private static final class GroupListModel extends AbstractListModel<NntpClient.Group> {
    final List<NntpClient.Group> rows;

    GroupListModel(List<NntpClient.Group> rows) {
      this.rows = List.copyOf(rows);
    }

    @Override
    public int getSize() {
      return rows.size();
    }

    @Override
    public NntpClient.Group getElementAt(int index) {
      return rows.get(index);
    }
  }

  private static final class ArticleModel extends AbstractTableModel {
    final String[] columns = {"State", "Subject", "From", "Date", "Bytes", "Score"};
    List<Threads.Row> rows = List.of();

    void setRows(List<Threads.Row> rows) {
      this.rows = rows;
      fireTableDataChanged();
    }

    void mark(String id, boolean read, boolean flag) {
      var updated = new ArrayList<>(rows);
      for (int i = 0; i < updated.size(); i++) {
        var row = updated.get(i);
        var a = row.article();
        if (a.messageId().equals(id)) {
          updated.set(
              i,
              new Threads.Row(
                  new Article(
                      a.number(),
                      a.subject(),
                      a.from(),
                      a.date(),
                      a.messageId(),
                      a.references(),
                      a.bytes(),
                      a.lines(),
                      read,
                      flag),
                  row.depth(),
                  row.score()));
          rows = List.copyOf(updated);
          fireTableRowsUpdated(i, i);
          return;
        }
      }
    }

    public int getRowCount() {
      return rows.size();
    }

    public int getColumnCount() {
      return columns.length;
    }

    public String getColumnName(int column) {
      return columns[column];
    }

    public Object getValueAt(int row, int column) {
      var r = rows.get(row);
      var a = r.article();
      return switch (column) {
        case 0 -> (a.flagged() ? "★ " : "") + (a.read() ? "" : "●");
        case 1 -> "  ".repeat(r.depth()) + a.subject();
        case 2 -> a.from();
        case 3 -> a.date();
        case 4 -> a.bytes();
        default -> r.score();
      };
    }
  }

  private final class Composer extends JDialog {
    final Server target;
    final JTextField from, to, subject, refs;
    final JTextArea text;
    final List<Path> files = new ArrayList<>();
    final DefaultListModel<String> fileNames = new DefaultListModel<>();
    final List<Mime.Attachment> embedded = new ArrayList<>();
    boolean changed, sending;

    Composer(
        Server target,
        String sender,
        String groups,
        String title,
        String content,
        String references) {
      super(PanxWindow.this, "Compose — " + target.name(), false);
      this.target = target;
      from = new JTextField(sender);
      to = new JTextField(groups);
      subject = new JTextField(title);
      refs = new JTextField(references);
      text = new JTextArea(content);
      setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
      setSize(800, 650);
      setLocationRelativeTo(PanxWindow.this);
      setLayout(new BorderLayout(5, 5));
      add(
          form(
              "From",
              from,
              "Newsgroups (comma separated)",
              to,
              "Subject",
              subject,
              "References",
              refs),
          BorderLayout.NORTH);
      text.setLineWrap(true);
      text.setWrapStyleWord(true);
      text.setMargin(new Insets(8, 8, 8, 8));
      add(new JScrollPane(text));
      var bottom = new JPanel(new BorderLayout());
      var list = new JList<>(fileNames);
      list.setVisibleRowCount(3);
      bottom.add(new JScrollPane(list));
      var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
      buttons.add(
          button(
              "Attach…",
              () -> {
                if (sending) return;
                Path file = chooseFile(false, null);
                if (file != null) {
                  files.add(file);
                  fileNames.addElement(file.toString());
                  changed = true;
                }
              }));
      buttons.add(
          button(
              "Remove attachment",
              () -> {
                if (!sending && list.getSelectedIndex() >= 0) {
                  int i = list.getSelectedIndex();
                  if (i < embedded.size()) embedded.remove(i);
                  else files.remove(i - embedded.size());
                  fileNames.remove(i);
                  changed = true;
                }
              }));
      buttons.add(button("Save draft", () -> send(true)));
      buttons.add(button("Post", () -> send(false)));
      bottom.add(buttons, BorderLayout.SOUTH);
      add(bottom, BorderLayout.SOUTH);
      var listener =
          new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
              changed = true;
            }

            public void removeUpdate(DocumentEvent e) {
              changed = true;
            }

            public void changedUpdate(DocumentEvent e) {
              changed = true;
            }
          };
      for (var field : List.of(from, to, subject, refs))
        field.getDocument().addDocumentListener(listener);
      text.getDocument().addDocumentListener(listener);
      addWindowListener(
          new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
              if (!sending
                  && (!changed
                      || JOptionPane.showConfirmDialog(
                              Composer.this,
                              "Discard unsaved changes?",
                              "Close composer",
                              JOptionPane.OK_CANCEL_OPTION)
                          == JOptionPane.OK_OPTION)) dispose();
            }
          });
    }

    void send(boolean draft) {
      if (sending || (!draft && !online(target))) return;
      if (!draft
          && JOptionPane.showConfirmDialog(
                  this,
                  "Post to " + to.getText() + " using " + target.name() + "?",
                  "Post article",
                  JOptionPane.OK_CANCEL_OPTION)
              != JOptionPane.OK_OPTION) return;
      String sender = from.getText(),
          groups = to.getText(),
          title = subject.getText(),
          content = text.getText(),
          references = refs.getText();
      List<Path> attached = List.copyOf(files);
      List<Mime.Attachment> saved = List.copyOf(embedded);
      sending = true;
      String oldTitle = getTitle();
      setTitle(draft ? "Saving draft…" : "Posting…");
      for (var field : List.of(from, to, subject, refs)) field.setEditable(false);
      text.setEditable(false);
      workers.submit(() -> {
        try {
          String raw = Mime.compose(sender, groups, title, content, references, attached, saved);
          if (draft) store.draft(raw);
          else news.post(target, raw);
          SwingUtilities.invokeLater(() -> {
            changed = false;
            sending = false;
            dispose();
            status.setText(draft ? "Draft saved with attachments" : "Article posted");
          });
        } catch (Exception e) {
          SwingUtilities.invokeLater(() -> {
            sending = false;
            setTitle(oldTitle);
            for (var field : List.of(from, to, subject, refs)) field.setEditable(true);
            text.setEditable(true);
            error(e);
          });
        }
      });
    }
  }
}
