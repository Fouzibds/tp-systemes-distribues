package org.example;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Map;

public class SupervisionDashboard extends JFrame implements SupervisionListener {
    private static final String[] SERVERS = {
            SupervisionLogger.SMTP,
            SupervisionLogger.POP3,
            SupervisionLogger.IMAP
    };

    private final Map<String, JLabel> statusLabels = new HashMap<>();
    private final Map<String, JLabel> connectionLabels = new HashMap<>();
    private final Map<String, JTextArea> logAreas = new HashMap<>();
    private final Map<String, JButton> startButtons = new HashMap<>();
    private final Map<String, JButton> stopButtons = new HashMap<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SupervisionDashboard dashboard = new SupervisionDashboard();
            dashboard.setVisible(true);
        });
    }

    public SupervisionDashboard() {
        super("Mail Server Supervision Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);

        SupervisionLogger.addListener(this);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopAllServers();
            }
        });
        buildUi();
        refreshAllStates();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        root.add(buildStatusPanel(), BorderLayout.NORTH);
        root.add(buildLogsPanel(), BorderLayout.CENTER);

        setContentPane(root);
    }

    private JPanel buildStatusPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(8, 8));

        JPanel grid = new JPanel(new GridLayout(1, SERVERS.length, 8, 8));
        for (String serverName : SERVERS) {
            JPanel panel = new JPanel(new GridLayout(4, 1, 4, 4));
            panel.setBorder(BorderFactory.createTitledBorder(serverName));

            JLabel status = new JLabel("Status: stopped");
            JLabel connections = new JLabel("Active connections: 0");
            JLabel port = new JLabel("Port: " + defaultPortFor(serverName));

            statusLabels.put(serverName, status);
            connectionLabels.put(serverName, connections);

            panel.add(status);
            panel.add(connections);
            panel.add(port);
            panel.add(buildControlsPanel(serverName));
            grid.add(panel);
        }
        wrapper.add(grid, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildControlsPanel(String serverName) {
        JPanel controls = new JPanel(new GridLayout(1, 2, 4, 4));

        JButton startButton = new JButton("Start " + serverName);
        startButton.addActionListener(event -> startServer(serverName));

        JButton stopButton = new JButton("Stop " + serverName);
        stopButton.addActionListener(event -> stopServer(serverName));

        startButtons.put(serverName, startButton);
        stopButtons.put(serverName, stopButton);

        controls.add(startButton);
        controls.add(stopButton);
        return controls;
    }

    private JTabbedPane buildLogsPanel() {
        JTabbedPane tabs = new JTabbedPane();
        for (String serverName : SERVERS) {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setLineWrap(false);
            logAreas.put(serverName, area);

            JScrollPane scrollPane = new JScrollPane(area);
            tabs.addTab(serverName + " logs", scrollPane);
        }
        return tabs;
    }

    private void startServer(String serverName) {
        switch (serverName) {
            case SupervisionLogger.SMTP:
                SmtpServer.startInBackground();
                break;
            case SupervisionLogger.POP3:
                Pop3Server.startInBackground();
                break;
            case SupervisionLogger.IMAP:
                ImapServer.startInBackground();
                break;
            default:
                break;
        }
    }

    private void stopServer(String serverName) {
        switch (serverName) {
            case SupervisionLogger.SMTP:
                SmtpServer.stopServer();
                break;
            case SupervisionLogger.POP3:
                Pop3Server.stopServer();
                break;
            case SupervisionLogger.IMAP:
                ImapServer.stopServer();
                break;
            default:
                break;
        }
    }

    private void stopAllServers() {
        SmtpServer.stopServer();
        Pop3Server.stopServer();
        ImapServer.stopServer();
    }

    @Override
    public void onLogEvent(SupervisionEvent event) {
        SwingUtilities.invokeLater(() -> {
            JTextArea area = logAreas.get(event.getServerName());
            if (area == null) {
                return;
            }

            area.append(event.formatForDisplay());
            area.append(System.lineSeparator());
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    @Override
    public void onServerStateChanged(String serverName, boolean running, int activeConnections) {
        SwingUtilities.invokeLater(() -> updateState(serverName, running, activeConnections));
    }

    private void refreshAllStates() {
        for (String serverName : SERVERS) {
            updateState(
                    serverName,
                    SupervisionLogger.isRunning(serverName),
                    SupervisionLogger.getActiveConnections(serverName)
            );
        }
    }

    private void updateState(String serverName, boolean running, int activeConnections) {
        JLabel status = statusLabels.get(serverName);
        JLabel connections = connectionLabels.get(serverName);
        if (status == null || connections == null) {
            return;
        }

        status.setText("Status: " + (running ? "running" : "stopped"));
        status.setForeground(running ? new Color(0, 128, 0) : Color.RED.darker());
        connections.setText("Active connections: " + activeConnections);

        JButton startButton = startButtons.get(serverName);
        JButton stopButton = stopButtons.get(serverName);
        if (startButton != null) {
            startButton.setEnabled(!running);
        }
        if (stopButton != null) {
            stopButton.setEnabled(running);
        }
    }

    private int defaultPortFor(String serverName) {
        switch (serverName) {
            case SupervisionLogger.SMTP:
                return 2525;
            case SupervisionLogger.POP3:
                return 1110;
            case SupervisionLogger.IMAP:
                return 1143;
            default:
                return -1;
        }
    }
}
