package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class ImapServer {
    private static final String SERVER_NAME = SupervisionLogger.IMAP;
    private static final int DEFAULT_PORT = 1143;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);

    private static volatile ServerSocket serverSocket;
    private static volatile Thread serverThread;

    public static void main(String[] args) {
        int port = getConfiguredPort("imap.port", "IMAP_PORT", DEFAULT_PORT);
        runServer(port);
    }

    public static Thread startInBackground() {
        int port = getConfiguredPort("imap.port", "IMAP_PORT", DEFAULT_PORT);
        synchronized (LIFECYCLE_LOCK) {
            if (serverThread != null
                    && serverThread.isAlive()
                    && serverSocket != null
                    && !serverSocket.isClosed()
                    && !STOP_REQUESTED.get()) {
                return serverThread;
            }

            STOP_REQUESTED.set(false);
            serverThread = new Thread(() -> runServer(port), "IMAP-Server");
            serverThread.setDaemon(true);
            serverThread.start();
            return serverThread;
        }
    }

    public static void stopServer() {
        ServerSocket socketToClose;
        synchronized (LIFECYCLE_LOCK) {
            socketToClose = serverSocket;
            if ((serverThread == null || !serverThread.isAlive())
                    && (socketToClose == null || socketToClose.isClosed())) {
                return;
            }
            STOP_REQUESTED.set(true);
        }

        SupervisionLogger.serverEvent(SERVER_NAME, "stop requested");
        if (socketToClose != null && !socketToClose.isClosed()) {
            try {
                socketToClose.close();
            } catch (IOException e) {
                SupervisionLogger.serverEvent(SERVER_NAME, "error while stopping server: " + e.getMessage());
            }
        }
    }

    private static void runServer(int port) {
        ServerSocket localServerSocket = null;
        boolean started = false;
        try {
            AuthClient.warmUp();
            localServerSocket = new ServerSocket(port);
            synchronized (LIFECYCLE_LOCK) {
                serverSocket = localServerSocket;
            }
            started = true;
            SupervisionLogger.setRunning(SERVER_NAME, true);
            SupervisionLogger.serverEvent(SERVER_NAME, "server started on port " + port);
            if (STOP_REQUESTED.get()) {
                localServerSocket.close();
                return;
            }
            while (!localServerSocket.isClosed()) {
                Socket clientSocket;
                try {
                    clientSocket = localServerSocket.accept();
                } catch (SocketException e) {
                    if (STOP_REQUESTED.get()) {
                        break;
                    }
                    throw e;
                }
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                SupervisionLogger.connectionOpened(SERVER_NAME, clientIp);
                new ImapSession(clientSocket).start();
            }
        } catch (IOException e) {
            if (!STOP_REQUESTED.get()) {
                SupervisionLogger.serverEvent(SERVER_NAME, "server stopped: " + e.getMessage());
            }
        } finally {
            boolean clearRunningState;
            synchronized (LIFECYCLE_LOCK) {
                clearRunningState = serverThread == null || serverThread == Thread.currentThread();
                if (serverSocket == localServerSocket) {
                    serverSocket = null;
                }
                if (serverThread == Thread.currentThread()) {
                    serverThread = null;
                }
            }
            if (started) {
                SupervisionLogger.serverEvent(SERVER_NAME, "server stopped");
            }
            if (localServerSocket != null && !localServerSocket.isClosed()) {
                try {
                    localServerSocket.close();
                } catch (IOException e) {
                    SupervisionLogger.serverEvent(SERVER_NAME, "error while releasing port: " + e.getMessage());
                }
            }
            STOP_REQUESTED.set(false);
            if (clearRunningState) {
                SupervisionLogger.setRunning(SERVER_NAME, false);
            }
        }
    }

    private static int getConfiguredPort(String propertyName, String environmentName, int defaultPort) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentName);
        }
        if (value == null || value.isBlank()) {
            return defaultPort;
        }

        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + value + "', using default " + defaultPort);
            return defaultPort;
        }
    }
}

class ImapSession extends Thread {
    private static final String SERVER_NAME = SupervisionLogger.IMAP;
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final String INBOX = "INBOX";
    private static final String SEEN_FLAG = "\\Seen";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final SimpleDateFormat IMAP_DATE_FORMAT = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US);

    private final Socket socket;
    private final String clientIp;
    private final EmailRepository emailRepository = new EmailRepository();
    private BufferedReader in;
    private OutputStream out;
    private ImapState state = ImapState.NOT_AUTHENTICATED;
    private String username;
    private final List<EmailRepository.EmailMessage> messages = new ArrayList<>();

    private enum ImapState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
    }

    public ImapSession(Socket socket) {
        this.socket = socket;
        this.clientIp = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();

            sendLine("* OK IMAP4rev2 educational server ready");

            String line;
            while (state != ImapState.LOGOUT && (line = in.readLine()) != null) {
                if (line.isBlank()) {
                    SupervisionLogger.command(SERVER_NAME, clientIp, "<empty>");
                    sendLine("* BAD Empty command");
                    continue;
                }

                ImapCommand command = parseCommand(line);
                if (command == null) {
                    SupervisionLogger.command(SERVER_NAME, clientIp, line);
                    sendLine("* BAD Missing command tag or command name");
                    continue;
                }

                logCommand(command);
                dispatch(command);
            }
        } catch (SocketTimeoutException e) {
            try {
                sendLine("* BYE Autologout; idle timeout");
            } catch (IOException ignored) {
            }
            SupervisionLogger.serverEvent(SERVER_NAME, "connection timed out: " + clientIp);
        } catch (IOException e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "session error for " + clientIp + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            SupervisionLogger.connectionClosed(SERVER_NAME, clientIp);
        }
    }

    private void dispatch(ImapCommand command) throws IOException {
        switch (command.name) {
            case "CAPABILITY":
                handleCapability(command);
                break;
            case "NOOP":
                sendTagged(command.tag, "OK", "NOOP completed");
                break;
            case "LIST":
                handleList(command);
                break;
            case "STATUS":
                handleStatus(command);
                break;
            case "LOGIN":
                handleLogin(command);
                break;
            case "SELECT":
                handleSelect(command, false);
                break;
            case "EXAMINE":
                handleSelect(command, true);
                break;
            case "FETCH":
                handleFetch(command, false);
                break;
            case "UID":
                handleUid(command);
                break;
            case "CLOSE":
                handleClose(command);
                break;
            case "STORE":
                handleStore(command);
                break;
            case "SEARCH":
                handleSearch(command);
                break;
            case "LOGOUT":
                handleLogout(command);
                break;
            default:
                sendTagged(command.tag, "BAD", "Command not implemented");
                break;
        }
    }

    private void handleCapability(ImapCommand command) throws IOException {
        sendLine("* CAPABILITY IMAP4rev1 IMAP4rev2 LITERAL+ UIDPLUS");
        sendTagged(command.tag, "OK", "CAPABILITY completed");
    }

    private void handleList(ImapCommand command) throws IOException {
        if (state == ImapState.NOT_AUTHENTICATED) {
            sendTagged(command.tag, "NO", "LIST requires authentication");
            return;
        }
        sendLine("* LIST (\\HasNoChildren) \"/\" \"INBOX\"");
        sendTagged(command.tag, "OK", "LIST completed");
    }

    private void handleStatus(ImapCommand command) throws IOException {
        if (state == ImapState.NOT_AUTHENTICATED) {
            sendTagged(command.tag, "NO", "STATUS requires authentication");
            return;
        }
        List<String> args = parseArguments(command.arguments);
        if (args.isEmpty() || !INBOX.equalsIgnoreCase(args.get(0))) {
            sendTagged(command.tag, "NO", "Only INBOX is supported");
            return;
        }
        loadMailbox();
        sendLine("* STATUS \"INBOX\" (MESSAGES " + messages.size()
                + " RECENT 0 UIDNEXT " + nextUid()
                + " UIDVALIDITY 1 UNSEEN " + unseenCount() + ")");
        sendTagged(command.tag, "OK", "STATUS completed");
    }

    private void handleLogin(ImapCommand command) throws IOException {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendTagged(command.tag, "NO", "LOGIN not allowed in " + state + " state");
            return;
        }

        List<String> args = parseArguments(command.arguments);
        if (args.size() < 2) {
            sendTagged(command.tag, "BAD", "LOGIN requires username and password");
            return;
        }

        String candidate = args.get(0);
        if (!isValidUsername(candidate)) {
            sendTagged(command.tag, "NO", "Invalid user name");
            return;
        }

        AuthClient.AuthResult authResult = AuthClient.authenticate(candidate, args.get(1));
        if (!authResult.isAvailable()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication service unavailable during LOGIN for " + candidate);
            sendTagged(command.tag, "NO", "LOGIN failed: authentication service unavailable");
            return;
        }
        if (!authResult.getValue()) {
            SupervisionLogger.serverEvent(SERVER_NAME, "authentication failed for " + candidate);
            sendTagged(command.tag, "NO", "LOGIN failed");
            return;
        }

        username = candidate;
        state = ImapState.AUTHENTICATED;
        sendTagged(command.tag, "OK", "LOGIN completed");
    }

    private void handleSelect(ImapCommand command, boolean readOnly) throws IOException {
        if (state != ImapState.AUTHENTICATED && state != ImapState.SELECTED) {
            sendTagged(command.tag, "NO", "Mailbox selection requires authentication");
            return;
        }

        List<String> args = parseArguments(command.arguments);
        if (args.size() != 1) {
            sendTagged(command.tag, "BAD", "SELECT requires a mailbox name");
            return;
        }
        if (!INBOX.equalsIgnoreCase(args.get(0))) {
            sendTagged(command.tag, "NO", "Only INBOX is supported");
            return;
        }

        loadMailbox();
        state = ImapState.SELECTED;

        sendLine("* FLAGS (" + SEEN_FLAG + ")");
        sendLine("* " + messages.size() + " EXISTS");
        sendLine("* 0 RECENT");
        sendLine("* OK [PERMANENTFLAGS (" + SEEN_FLAG + ")] Limited flags supported");
        sendLine("* OK [UIDVALIDITY 1] UIDs valid");
        sendLine("* OK [UIDNEXT " + nextUid() + "] Predicted next UID");
        int firstUnseen = firstUnseenMessageNumber();
        if (firstUnseen > 0) {
            sendLine("* OK [UNSEEN " + firstUnseen + "] First unseen message");
        }
        sendTagged(command.tag, "OK", (readOnly ? "[READ-ONLY] EXAMINE completed" : "[READ-WRITE] SELECT completed"));
    }

    private void handleFetch(ImapCommand command, boolean uidMode) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(command.tag, "NO", "FETCH requires a selected mailbox");
            return;
        }

        debugFetch((uidMode ? "UID FETCH " : "FETCH ") + command.arguments);
        FetchRequest request = parseFetchRequest(command.arguments);
        if (request == null) {
            sendTagged(command.tag, "BAD", "FETCH syntax error");
            return;
        }

        List<Integer> indexes = uidMode ? parseUidSet(request.messageSet) : parseSequenceSet(request.messageSet);
        if (indexes.isEmpty()) {
            sendTagged(command.tag, "NO", "No matching messages");
            return;
        }

        for (Integer index : indexes) {
            if (!messageExists(index)) {
                continue;
            }
            sendFetchResponse(index, request.items, uidMode);
        }

        sendTagged(command.tag, "OK", "FETCH completed");
    }

    private void handleUid(ImapCommand command) throws IOException {
        String[] parts = command.arguments.trim().split("\\s+", 2);
        if (parts.length == 0 || parts[0].isBlank()) {
            sendTagged(command.tag, "BAD", "UID requires a subcommand");
            return;
        }
        String subcommand = parts[0].toUpperCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        if ("FETCH".equals(subcommand)) {
            handleFetch(new ImapCommand(command.tag, "FETCH", rest), true);
        } else {
            sendTagged(command.tag, "BAD", "Only UID FETCH is supported");
        }
    }

    private void handleClose(ImapCommand command) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(command.tag, "NO", "CLOSE requires selected mailbox");
            return;
        }
        messages.clear();
        state = ImapState.AUTHENTICATED;
        sendTagged(command.tag, "OK", "CLOSE completed");
    }

    private void handleStore(ImapCommand command) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(command.tag, "NO", "STORE requires a selected mailbox");
            return;
        }

        List<String> args = parseArguments(command.arguments);
        if (args.size() != 3) {
            sendTagged(command.tag, "BAD", "STORE syntax: STORE n +FLAGS \\Seen or STORE n -FLAGS \\Seen");
            return;
        }

        Integer index = parseMessageIndex(args.get(0));
        if (index == null) {
            sendTagged(command.tag, "BAD", "Invalid message sequence number");
            return;
        }
        if (!messageExists(index)) {
            sendTagged(command.tag, "NO", "No such message");
            return;
        }

        String operation = args.get(1).toUpperCase(Locale.ROOT);
        String flag = args.get(2);
        if (!SEEN_FLAG.equalsIgnoreCase(flag)) {
            sendTagged(command.tag, "BAD", "Only \\Seen is supported");
            return;
        }

        EmailRepository.EmailMessage message = messages.get(index);
        if ("+FLAGS".equals(operation)) {
            setSeen(message, true);
        } else if ("-FLAGS".equals(operation)) {
            setSeen(message, false);
        } else {
            sendTagged(command.tag, "BAD", "Only +FLAGS and -FLAGS are supported");
            return;
        }

        loadMailbox();
        message = messages.get(index);
        sendLine("* " + (index + 1) + " FETCH (FLAGS (" + flagsFor(message) + "))");
        sendTagged(command.tag, "OK", "STORE completed");
    }

    private void handleSearch(ImapCommand command) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(command.tag, "NO", "SEARCH requires a selected mailbox");
            return;
        }

        List<String> args = parseArguments(command.arguments);
        if (args.size() < 2) {
            sendTagged(command.tag, "BAD", "SEARCH syntax: SEARCH FROM keyword or SEARCH SUBJECT keyword");
            return;
        }

        String criterion = args.get(0).toUpperCase(Locale.ROOT);
        String keyword = joinArguments(args, 1).toLowerCase(Locale.ROOT);
        if ("FROM".equals(criterion)) {
        } else if ("SUBJECT".equals(criterion)) {
        } else {
            sendTagged(command.tag, "BAD", "Only FROM and SUBJECT searches are supported");
            return;
        }

        List<Integer> matches = new ArrayList<>();
        try {
            List<EmailRepository.EmailMessage> searchResults = emailRepository.searchEmails(username, criterion, keyword);
            for (EmailRepository.EmailMessage found : searchResults) {
                int sequenceNumber = sequenceNumberFor(found.getId());
                if (sequenceNumber > 0) {
                    matches.add(sequenceNumber);
                }
            }
        } catch (Exception e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "could not search mailbox in MySQL for " + username + ": " + e.getMessage());
            sendTagged(command.tag, "NO", "SEARCH failed: database unavailable");
            return;
        }

        sendLine("* SEARCH" + formatSearchMatches(matches));
        sendTagged(command.tag, "OK", "SEARCH completed");
    }

    private void handleLogout(ImapCommand command) throws IOException {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP4rev2 server logging out");
        sendTagged(command.tag, "OK", "LOGOUT completed");
    }

    private void loadMailbox() {
        messages.clear();
        try {
            messages.addAll(emailRepository.fetchEmails(username));
        } catch (Exception e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "could not load IMAP mailbox from MySQL for " + username + ": " + e.getMessage());
        }
    }

    private String readMessage(EmailRepository.EmailMessage message) {
        return message.toRfcMessage();
    }

    private String readHeader(EmailRepository.EmailMessage message) {
        String header = message.toHeader();
        return header.endsWith("\r\n\r\n") ? header : header + "\r\n";
    }

    private String readBody(EmailRepository.EmailMessage message) {
        return message.getBody().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    private void sendFetchResponse(int index, String requestedItems, boolean uidMode) throws IOException {
        EmailRepository.EmailMessage message = messages.get(index);
        int sequenceNumber = index + 1;
        debugFetch("FETCH request seq=" + sequenceNumber + " uid=" + message.getId() + " items=" + requestedItems);
        debugFetchMessage(sequenceNumber, message);
        String items = normalizeFetchItems(requestedItems);
        List<String> simpleParts = new ArrayList<>();
        List<LiteralFetch> literalParts = new ArrayList<>();

        if (uidMode || containsFetchItem(items, "UID")) {
            simpleParts.add("UID " + message.getId());
        }
        if (containsFetchItem(items, "FLAGS")) {
            simpleParts.add("FLAGS (" + flagsFor(message) + ")");
        }
        if (containsFetchItem(items, "ENVELOPE")) {
            simpleParts.add("ENVELOPE " + envelopeFor(message));
        }
        if (containsFetchItem(items, "RFC822.SIZE")) {
            simpleParts.add("RFC822.SIZE " + message.size());
        }
        if (containsFetchItem(items, "INTERNALDATE")) {
            simpleParts.add("INTERNALDATE \"" + IMAP_DATE_FORMAT.format(message.getCreatedAt()) + "\"");
        }
        if (containsFetchItem(items, "BODYSTRUCTURE")) {
            simpleParts.add("BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"8BIT\" "
                    + bodyOctets(message) + " " + bodyLineCount(message) + ")");
        }

        for (BodyFetch bodyFetch : detectBodyFetches(items)) {
            if (!bodyFetch.peek) {
                setSeen(message, true);
            }
            if (bodyFetch.header) {
                literalParts.add(literalFetch(bodyFetch.responseItem, readHeader(message), bodyFetch.partial));
            } else if (bodyFetch.fullMessage) {
                literalParts.add(literalFetch(bodyFetch.responseItem, readMessage(message), bodyFetch.partial));
            } else if (bodyFetch.textBody) {
                literalParts.add(literalFetch(bodyFetch.responseItem, readBody(message), bodyFetch.partial));
            }
        }
        if (containsFetchItem(items, "RFC822.TEXT")) {
            literalParts.add(literalFetch("RFC822.TEXT", readBody(message), null));
        }
        if (containsFetchItem(items, "RFC822.HEADER")) {
            literalParts.add(literalFetch("RFC822.HEADER", readHeader(message), null));
        }
        if (containsFetchItem(items, "RFC822")) {
            setSeen(message, true);
            literalParts.add(literalFetch("RFC822", readMessage(message), null));
        }

        if (literalParts.isEmpty()) {
            sendLine("* " + sequenceNumber + " FETCH (" + String.join(" ", simpleParts) + ")");
            return;
        }

        writeFetchWithLiterals(sequenceNumber, simpleParts, literalParts);
    }

    private void writeFetchWithLiterals(int sequenceNumber, List<String> simpleParts, List<LiteralFetch> literalParts) throws IOException {
        String prefix = simpleParts.isEmpty() ? "" : String.join(" ", simpleParts) + " ";
        for (int i = 0; i < literalParts.size(); i++) {
            LiteralFetch literal = literalParts.get(i);
            debugFetch("FETCH literal seq=" + sequenceNumber + " item=" + literal.responseItem
                    + " bytes=" + literal.bytes.length);
            String line;
            if (i == 0) {
                line = "* " + sequenceNumber + " FETCH (" + prefix + literal.responseItem + " {" + literal.bytes.length + "}";
            } else {
                line = " " + literal.responseItem + " {" + literal.bytes.length + "}";
            }
            writeLineNoFlush(line);
            out.write(literal.bytes);
            if (i < literalParts.size() - 1) {
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write("\r\n)\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        SupervisionLogger.response(SERVER_NAME, clientIp, "FETCH completed with " + literalParts.size() + " literal part(s)");
    }

    private void sendTagged(String tag, String status, String text) throws IOException {
        sendLine(tag + " " + status + " " + text);
    }

    private void sendLine(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        SupervisionLogger.response(SERVER_NAME, clientIp, line);
    }

    private void writeLineNoFlush(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        SupervisionLogger.response(SERVER_NAME, clientIp, line);
    }

    private ImapCommand parseCommand(String line) {
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length < 2) {
            return null;
        }

        return new ImapCommand(
                parts[0],
                parts[1].toUpperCase(Locale.ROOT),
                parts.length == 3 ? parts[2].trim() : ""
        );
    }

    private FetchRequest parseFetchRequest(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        String trimmed = arguments.trim();
        int split = trimmed.indexOf(' ');
        if (split < 0) {
            return null;
        }
        String messageSet = trimmed.substring(0, split).trim();
        String items = trimmed.substring(split + 1).trim();
        if (messageSet.isBlank() || items.isBlank()) {
            return null;
        }
        return new FetchRequest(messageSet, items);
    }

    private List<Integer> parseSequenceSet(String value) {
        List<Integer> indexes = new ArrayList<>();
        for (String part : value.split(",")) {
            addSequencePart(indexes, part.trim(), false);
        }
        return indexes;
    }

    private List<Integer> parseUidSet(String value) {
        List<Integer> indexes = new ArrayList<>();
        for (String part : value.split(",")) {
            addSequencePart(indexes, part.trim(), true);
        }
        return indexes;
    }

    private void addSequencePart(List<Integer> indexes, String part, boolean uidMode) {
        if (part.isBlank()) {
            return;
        }
        int colon = part.indexOf(':');
        if (colon >= 0) {
            int start = parseSetNumber(part.substring(0, colon), uidMode, true);
            int end = parseSetNumber(part.substring(colon + 1), uidMode, false);
            if (start < 0 || end < 0) {
                return;
            }
            int low = Math.min(start, end);
            int high = Math.max(start, end);
            for (int i = low; i <= high && i < messages.size(); i++) {
                addUnique(indexes, i);
            }
            return;
        }

        int index = parseSetNumber(part, uidMode, true);
        if (messageExists(index)) {
            addUnique(indexes, index);
        }
    }

    private int parseSetNumber(String value, boolean uidMode, boolean firstValue) {
        String trimmed = value.trim();
        if ("*".equals(trimmed)) {
            return messages.isEmpty() ? -1 : messages.size() - 1;
        }
        try {
            int number = Integer.parseInt(trimmed);
            if (!uidMode) {
                return number - 1;
            }
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getId() == number) {
                    return i;
                }
            }
            return firstValue ? -1 : messages.size() - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void addUnique(List<Integer> indexes, int index) {
        if (!indexes.contains(index)) {
            indexes.add(index);
        }
    }

    private String normalizeFetchItems(String items) {
        String normalized = items.trim();
        if (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private boolean containsFetchItem(String items, String item) {
        if ("RFC822".equals(item)) {
            return items.equals("RFC822") || items.contains(" RFC822 ") || items.startsWith("RFC822 ") || items.endsWith(" RFC822");
        }
        return items.contains(item);
    }

    private List<BodyFetch> detectBodyFetches(String items) {
        List<BodyFetch> fetches = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < items.length()) {
            int peekStart = items.indexOf("BODY.PEEK[", searchFrom);
            int bodyStart = items.indexOf("BODY[", searchFrom);
            if (peekStart < 0 && bodyStart < 0) {
                break;
            }

            boolean peek = peekStart >= 0 && (bodyStart < 0 || peekStart < bodyStart);
            int start = peek ? peekStart : bodyStart;
            String marker = peek ? "BODY.PEEK[" : "BODY[";
            int sectionStart = start + marker.length();
            int sectionEnd = items.indexOf(']', sectionStart);
            if (sectionEnd < 0) {
                break;
            }

            String section = items.substring(sectionStart, sectionEnd).trim();
            int afterSection = sectionEnd + 1;
            PartialFetch partial = null;
            if (afterSection < items.length() && items.charAt(afterSection) == '<') {
                int partialEnd = items.indexOf('>', afterSection);
                if (partialEnd > afterSection) {
                    partial = parsePartial(items.substring(afterSection, partialEnd + 1));
                    afterSection = partialEnd + 1;
                }
            }

            String responseItem = "BODY[" + section + "]";
            boolean header = section.startsWith("HEADER");
            boolean fullMessage = section.isEmpty();
            boolean textBody = "TEXT".equals(section) || "1".equals(section);
            if (header || fullMessage || textBody) {
                fetches.add(new BodyFetch(responseItem, header, fullMessage, textBody, peek, partial));
            }
            searchFrom = afterSection;
        }
        return fetches;
    }

    private PartialFetch parsePartial(String partial) {
        if (partial == null || partial.length() < 3 || partial.charAt(0) != '<' || partial.charAt(partial.length() - 1) != '>') {
            return null;
        }
        String value = partial.substring(1, partial.length() - 1);
        String[] pieces = value.split("\\.", 2);
        try {
            int start = Integer.parseInt(pieces[0]);
            Integer maxBytes = pieces.length == 2 ? Integer.parseInt(pieces[1]) : null;
            if (start < 0 || (maxBytes != null && maxBytes < 0)) {
                return null;
            }
            return new PartialFetch(start, maxBytes);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LiteralFetch literalFetch(String responseItem, String value, PartialFetch partial) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (partial != null) {
            int start = Math.min(partial.start, bytes.length);
            int end = partial.maxBytes == null ? bytes.length : Math.min(bytes.length, start + partial.maxBytes);
            bytes = Arrays.copyOfRange(bytes, start, end);
            responseItem = responseItem + "<" + partial.start + ">";
        }
        return new LiteralFetch(responseItem, bytes);
    }

    private int bodyOctets(EmailRepository.EmailMessage message) {
        return readBody(message).getBytes(StandardCharsets.UTF_8).length;
    }

    private int bodyLineCount(EmailRepository.EmailMessage message) {
        String body = readBody(message);
        if (body.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private void debugFetchMessage(int sequenceNumber, EmailRepository.EmailMessage message) {
        String body = message.getBody();
        String preview = body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n");
        if (preview.length() > 100) {
            preview = preview.substring(0, 100);
        }
        debugFetch("FETCH message seq=" + sequenceNumber
                + " id=" + message.getId()
                + " subject=\"" + logText(message.getSubject()) + "\""
                + " bodyLengthChars=" + body.length()
                + " bodyLengthBytes=" + body.getBytes(StandardCharsets.UTF_8).length
                + " bodyPreview=\"" + logText(preview) + "\"");
    }

    private String logText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void debugFetch(String message) {
        String line = "DEBUG IMAP " + message;
        System.out.println(line);
        SupervisionLogger.serverEvent(SERVER_NAME, line);
    }

    private String envelopeFor(EmailRepository.EmailMessage message) {
        return "("
                + quote(IMAP_DATE_FORMAT.format(message.getCreatedAt())) + " "
                + quote(message.getSubject()) + " "
                + addressList(message.getSender()) + " "
                + addressList(message.getSender()) + " "
                + addressList(message.getSender()) + " "
                + addressList(message.getRecipient()) + " "
                + "NIL NIL NIL "
                + quote("<" + message.getId() + "@localhost>")
                + ")";
    }

    private String addressList(String address) {
        String normalized = address == null ? "" : address.trim();
        int at = normalized.indexOf('@');
        String mailbox = at > 0 ? normalized.substring(0, at) : normalized;
        String host = at > 0 ? normalized.substring(at + 1) : "localhost";
        return "((NIL NIL " + quote(mailbox) + " " + quote(host) + "))";
    }

    private String quote(String value) {
        if (value == null) {
            return "NIL";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private int nextUid() {
        int max = 1;
        for (EmailRepository.EmailMessage message : messages) {
            max = Math.max(max, message.getId() + 1);
        }
        return max;
    }

    private List<String> parseArguments(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (quoted && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    private Integer parseMessageIndex(String value) {
        try {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Integer.parseInt(value.trim()) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean messageExists(int index) {
        return index >= 0 && index < messages.size();
    }

    private int firstUnseenMessageNumber() {
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).isSeen()) {
                return i + 1;
            }
        }
        return -1;
    }

    private int unseenCount() {
        int count = 0;
        for (EmailRepository.EmailMessage message : messages) {
            if (!message.isSeen()) {
                count++;
            }
        }
        return count;
    }

    private void setSeen(EmailRepository.EmailMessage message, boolean seen) {
        try {
            if (!emailRepository.markEmailSeen(message.getId(), username, seen)) {
                SupervisionLogger.serverEvent(SERVER_NAME, "email " + message.getId() + " seen flag was not updated in MySQL");
            }
        } catch (Exception e) {
            SupervisionLogger.serverEvent(SERVER_NAME, "could not update seen flag in MySQL for email " + message.getId() + ": " + e.getMessage());
        }
    }

    private String flagsFor(EmailRepository.EmailMessage message) {
        return message.isSeen() ? SEEN_FLAG : "";
    }

    private int sequenceNumberFor(int emailId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId() == emailId) {
                return i + 1;
            }
        }
        return -1;
    }

    private boolean isValidUsername(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (candidate.contains("/") || candidate.contains("\\") || candidate.contains("..")) {
            return false;
        }
        if (candidate.endsWith(".")) {
            return false;
        }
        return USERNAME_PATTERN.matcher(candidate).matches();
    }

    private String joinArguments(List<String> args, int startIndex) {
        StringBuilder joined = new StringBuilder();
        for (int i = startIndex; i < args.size(); i++) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(args.get(i));
        }
        return joined.toString();
    }

    private String formatSearchMatches(List<Integer> matches) {
        if (matches.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Integer match : matches) {
            builder.append(' ').append(match);
        }
        return builder.toString();
    }

    private void logCommand(ImapCommand command) {
        if ("LOGIN".equals(command.name)) {
            List<String> args = parseArguments(command.arguments);
            String loggedUser = args.isEmpty() ? "<missing>" : args.get(0);
            SupervisionLogger.command(SERVER_NAME, clientIp, command.tag + " LOGIN " + loggedUser + " <redacted>");
        } else {
            SupervisionLogger.command(SERVER_NAME, clientIp, command.tag + " " + command.name
                    + (command.arguments.isEmpty() ? "" : " " + command.arguments));
        }
    }

    private static class ImapCommand {
        private final String tag;
        private final String name;
        private final String arguments;

        private ImapCommand(String tag, String name, String arguments) {
            this.tag = tag;
            this.name = name;
            this.arguments = arguments;
        }
    }

    private static class FetchRequest {
        private final String messageSet;
        private final String items;

        private FetchRequest(String messageSet, String items) {
            this.messageSet = messageSet;
            this.items = items;
        }
    }

    private static class BodyFetch {
        private final String responseItem;
        private final boolean header;
        private final boolean fullMessage;
        private final boolean textBody;
        private final boolean peek;
        private final PartialFetch partial;

        private BodyFetch(String responseItem, boolean header, boolean fullMessage, boolean textBody,
                          boolean peek, PartialFetch partial) {
            this.responseItem = responseItem;
            this.header = header;
            this.fullMessage = fullMessage;
            this.textBody = textBody;
            this.peek = peek;
            this.partial = partial;
        }
    }

    private static class PartialFetch {
        private final int start;
        private final Integer maxBytes;

        private PartialFetch(int start, Integer maxBytes) {
            this.start = start;
            this.maxBytes = maxBytes;
        }
    }

    private static class LiteralFetch {
        private final String responseItem;
        private final byte[] bytes;

        private LiteralFetch(String responseItem, byte[] bytes) {
            this.responseItem = responseItem;
            this.bytes = bytes;
        }
    }
}
