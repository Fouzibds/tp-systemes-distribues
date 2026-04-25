package org.example;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WebTemplates {
    private static final SimpleDateFormat SHORT_DATE = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
    private static final SimpleDateFormat FULL_DATE = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static volatile int instancePort = 8080;

    private WebTemplates() {
    }

    public static void setInstancePort(int port) {
        instancePort = port;
    }

    public static String loginPage(String error) {
        return page("Login", """
                <main class="login-shell">
                    <section class="login-panel">
                        <div class="brand-mark">WM</div>
                        <h1>Web Mail TP</h1>
                        <p class="muted">A local Gmail-inspired interface for the distributed mail TP.</p>
                        %s
                        <form method="post" action="/login" class="stack">
                            <label>Username<input name="username" autocomplete="username" required></label>
                            <label>Password<input type="password" name="password" autocomplete="current-password" required></label>
                            <button class="primary" type="submit">Sign in</button>
                        </form>
                    </section>
                </main>
                """.formatted(alert(error, "error")));
    }

    public static String mailboxPage(String username, String folder, List<EmailRepository.EmailMessage> messages,
                                     String query, String notice, String error, int unreadCount) {
        String normalizedFolder = normalizeFolder(folder);
        String title = titleFor(normalizedFolder);
        String subtitle = "search".equals(normalizedFolder)
                ? "Results for \"" + escape(query) + "\""
                : subtitleFor(normalizedFolder);
        StringBuilder rows = new StringBuilder();
        if (messages.isEmpty()) {
            rows.append("""
                    <div class="empty-state">
                        <h2>No messages here</h2>
                        <p>This view has no matching email yet.</p>
                    </div>
                    """);
        } else {
            for (EmailRepository.EmailMessage message : messages) {
                rows.append(messageRow(message, normalizedFolder));
            }
        }

        String content = """
                <section class="mail-view">
                    <div class="content-head">
                        <div>
                            <h1>%s</h1>
                            <p class="muted">%s</p>
                        </div>
                    </div>
                    %s
                    %s
                    %s
                    <form id="bulkForm" method="post" action="/bulk">
                        <input type="hidden" name="folder" value="%s">
                        <input type="hidden" id="bulkAction" name="action" value="">
                        <input type="hidden" id="bulkIds" name="ids" value="">
                        <div class="message-list">%s</div>
                    </form>
                </section>
                """.formatted(
                escape(title),
                subtitle,
                alert(notice, "success"),
                alert(error, "error"),
                toolbar(normalizedFolder),
                escape(normalizedFolder),
                rows);
        return appPage(username, normalizedFolder, content, query, unreadCount);
    }

    public static String messagePage(String username, EmailRepository.EmailMessage message, String error, int unreadCount) {
        if (message == null) {
            String content = """
                    <section class="mail-view">
                        <div class="empty-state">
                            <h2>Message not found</h2>
                            <p>The message may have been deleted or the id is invalid.</p>
                            <a class="button-link secondary" href="/inbox">Back to inbox</a>
                        </div>
                    </section>
                    """;
            return appPage(username, "inbox", content, "", unreadCount);
        }

        String content = """
                <section class="mail-view">
                    <div class="message-actions">
                        <a class="button-link secondary" href="/inbox">Back</a>
                        <form method="post" action="/bulk">
                            <input type="hidden" name="folder" value="inbox">
                            <input type="hidden" name="ids" value="%d">
                            <button class="danger" name="action" value="delete" type="submit">Delete</button>
                        </form>
                    </div>
                    %s
                    <article class="message-card">
                        <div class="message-title-line">
                            <h1>%s</h1>
                            <span class="badge">%s</span>
                        </div>
                        <div class="message-meta">
                            <span><strong>From:</strong> %s</span>
                            <span><strong>To:</strong> %s</span>
                            <span><strong>Date:</strong> %s</span>
                            <span><strong>Status:</strong> %s %s</span>
                        </div>
                        <pre class="message-body">%s</pre>
                    </article>
                </section>
                """.formatted(
                message.getId(),
                alert(error, "error"),
                escape(message.getSubject()),
                escape(message.getCategory()),
                escape(message.getSender()),
                escape(message.getRecipient()),
                escape(FULL_DATE.format(message.getCreatedAt())),
                message.isStarred() ? "Starred" : "Not starred",
                message.isImportant() ? "Important" : "",
                escape(message.getBody()));
        return appPage(username, "message", content, "", unreadCount);
    }

    public static String composePage(String username, String to, String subject, String body, String error, int unreadCount) {
        String content = """
                <section class="mail-view compose-shell">
                    <div class="compose-card">
                        <div class="content-head">
                            <div>
                                <h1>New message</h1>
                                <p class="muted">Delivery goes through the local SMTP server on port 2525.</p>
                            </div>
                        </div>
                        %s
                        <form method="post" action="/send" class="compose-form">
                            <label>To<input name="to" value="%s" placeholder="user2 or user2@localhost" required></label>
                            <label>Subject<input name="subject" value="%s" required></label>
                            <label>Body<textarea name="body" rows="13" required>%s</textarea></label>
                            <div class="form-actions">
                                <button class="primary" type="submit">Send</button>
                                <a class="button-link secondary" href="/inbox">Cancel</a>
                            </div>
                        </form>
                    </div>
                </section>
                """.formatted(alert(error, "error"), escape(to), escape(subject), escape(body));
        return appPage(username, "compose", content, "", unreadCount);
    }

    public static String redirectPage(String target) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url="
                + escape(target) + "\"></head><body></body></html>";
    }

    private static String appPage(String username, String activeFolder, String content, String query, int unreadCount) {
        String body = """
                <div class="app-shell">
                    <aside class="sidebar">
                        <a class="compose-button" href="/compose">Compose</a>
                        <nav>
                            %s
                        </nav>
                    </aside>
                    <main class="app-main">
                        <header class="topbar">
                            <a class="app-name" href="/inbox">Web Mail TP</a>
                            <form method="get" action="/search" class="search-form">
                                <input name="q" value="%s" placeholder="Search mail">
                                <select name="field" title="Search field">
                                    <option value="ALL">All</option>
                                    <option value="FROM">From</option>
                                    <option value="TO">To</option>
                                    <option value="SUBJECT">Subject</option>
                                    <option value="BODY">Body</option>
                                </select>
                                <label class="filter"><input type="checkbox" name="unread"> Unread</label>
                                <label class="filter"><input type="checkbox" name="starred"> Starred</label>
                                <label class="filter"><input type="checkbox" name="important"> Important</label>
                                <button class="search-button" type="submit">Search</button>
                            </form>
                            <div class="account">
                                <span>%s</span>
                                <a href="/logout">Logout</a>
                            </div>
                        </header>
                        %s
                    </main>
                </div>
                %s
                """.formatted(sidebar(activeFolder, unreadCount), escape(query), escape(username), content, script());
        return page("Web Mail", body);
    }

    private static String sidebar(String activeFolder, int unreadCount) {
        return navItem("Inbox", "/inbox", "inbox", activeFolder, unreadCount)
                + navItem("Sent", "/sent", "sent", activeFolder, 0)
                + navItem("Trash", "/trash", "trash", activeFolder, 0)
                + navItem("Starred", "/folder?name=starred", "starred", activeFolder, 0)
                + navItem("Important", "/folder?name=important", "important", activeFolder, 0)
                + navItem("Spam", "/folder?name=spam", "spam", activeFolder, 0)
                + navItem("Promotions", "/folder?name=promotions", "promotions", activeFolder, 0)
                + navItem("Social", "/folder?name=social", "social", activeFolder, 0)
                + navItem("Updates", "/folder?name=updates", "updates", activeFolder, 0)
                + "<span class=\"nav-item disabled\">Drafts</span>";
    }

    private static String navItem(String label, String href, String id, String active, int count) {
        String badge = count > 0 ? "<span class=\"count\">" + count + "</span>" : "";
        return "<a class=\"nav-item " + (id.equals(active) ? "active" : "") + "\" href=\"" + href + "\"><span>"
                + escape(label) + "</span>" + badge + "</a>";
    }

    private static String toolbar(String folder) {
        boolean trash = "trash".equals(folder);
        return """
                <div class="toolbar">
                    <label class="select-all"><input id="selectAll" type="checkbox"> Select all</label>
                    <button type="button" onclick="bulkAction('delete')">Delete</button>
                    %s
                    %s
                    <button type="button" onclick="bulkAction('read')">Mark read</button>
                    <button type="button" onclick="bulkAction('unread')">Mark unread</button>
                    <button type="button" onclick="bulkAction('star')">Star</button>
                    <button type="button" onclick="bulkAction('unstar')">Unstar</button>
                    <button type="button" onclick="bulkAction('important')">Important</button>
                    <button type="button" onclick="bulkAction('unimportant')">Unimportant</button>
                    <button type="button" onclick="bulkAction('spam')">Spam</button>
                    <button type="button" onclick="bulkAction('not-spam')">Not spam</button>
                </div>
                """.formatted(
                trash ? "<button type=\"button\" onclick=\"bulkAction('restore')\">Restore</button>" : "",
                trash ? "<button class=\"danger-light\" type=\"button\" onclick=\"bulkAction('permanent-delete')\">Delete forever</button>" : "");
    }

    private static String messageRow(EmailRepository.EmailMessage message, String folder) {
        String unreadClass = message.isSeen() ? "" : " unread";
        String primaryName = "sent".equals(folder) ? message.getRecipient() : message.getSender();
        return """
                <div class="message-row%s">
                    <input class="message-check" type="checkbox" value="%d">
                    <span class="row-icon">%s</span>
                    <span class="row-icon">%s</span>
                    <a class="sender" href="/message?id=%d">%s</a>
                    <a class="subject" href="/message?id=%d">%s</a>
                    <span class="badge">%s</span>
                    <span class="preview">%s</span>
                    <span class="date">%s</span>
                </div>
                """.formatted(
                unreadClass,
                message.getId(),
                message.isStarred() ? "★" : "☆",
                message.isImportant() ? "!" : "",
                message.getId(),
                escape(primaryName),
                message.getId(),
                escape(message.getSubject()),
                escape(message.getCategory()),
                escape(preview(message.getBody())),
                escape(formatShortDate(message.getCreatedAt())));
    }

    private static String titleFor(String folder) {
        switch (folder) {
            case "sent":
                return "Sent";
            case "trash":
                return "Trash";
            case "starred":
                return "Starred";
            case "important":
                return "Important";
            case "spam":
                return "Spam";
            case "promotions":
                return "Promotions";
            case "social":
                return "Social";
            case "updates":
                return "Updates";
            case "search":
                return "Search";
            default:
                return "Inbox";
        }
    }

    private static String subtitleFor(String folder) {
        switch (folder) {
            case "sent":
                return "Messages sent through the local SMTP server.";
            case "trash":
                return "Deleted incoming messages. Restore them or delete forever.";
            case "spam":
                return "Messages marked as spam.";
            case "promotions":
            case "social":
            case "updates":
                return "Automatically classified with simple keyword rules.";
            default:
                return "Messages received from the distributed mail system.";
        }
    }

    private static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s</title>
                    %s
                </head>
                <body>
                    %s
                    <div class="instance-info">Served by instance port %d</div>
                </body>
                </html>
                """.formatted(escape(title), style(), body, instancePort);
    }

    private static String alert(String message, String type) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "<div class=\"alert " + type + "\">" + escape(message) + "</div>";
    }

    private static String preview(String body) {
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 96 ? compact : compact.substring(0, 93) + "...";
    }

    private static String formatShortDate(Date date) {
        return date == null ? "" : SHORT_DATE.format(date);
    }

    private static String normalizeFolder(String folder) {
        return folder == null || folder.isBlank() ? "inbox" : folder.toLowerCase(Locale.ROOT);
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String script() {
        return """
                <script>
                const selectAll = document.getElementById('selectAll');
                if (selectAll) {
                    selectAll.addEventListener('change', () => {
                        document.querySelectorAll('.message-check').forEach(box => box.checked = selectAll.checked);
                    });
                }
                function bulkAction(action) {
                    const selected = Array.from(document.querySelectorAll('.message-check:checked')).map(box => box.value);
                    if (selected.length === 0) {
                        alert('Select at least one message first.');
                        return;
                    }
                    document.getElementById('bulkAction').value = action;
                    document.getElementById('bulkIds').value = selected.join(',');
                    document.getElementById('bulkForm').submit();
                }
                </script>
                """;
    }

    private static String style() {
        return """
                <style>
                :root {
                    --bg: #f5f7fb;
                    --panel: #ffffff;
                    --text: #1f2937;
                    --muted: #667085;
                    --line: #e3e8ef;
                    --brand: #2563eb;
                    --brand-dark: #1d4ed8;
                    --soft: #eef4ff;
                    --danger: #dc2626;
                    --success: #15803d;
                    --shadow: 0 16px 34px rgba(15, 23, 42, .08);
                }
                * { box-sizing: border-box; }
                body { margin: 0; background: var(--bg); color: var(--text); font-family: Inter, Segoe UI, Roboto, Arial, sans-serif; }
                a { color: inherit; text-decoration: none; }
                h1 { margin: 0 0 6px; font-size: 28px; letter-spacing: 0; }
                .muted { color: var(--muted); margin: 0; }
                .login-shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: linear-gradient(135deg, #eef6ff 0%, #f8fafc 52%, #f2fbf7 100%); }
                .login-panel { width: min(430px, 100%); background: var(--panel); border: 1px solid var(--line); border-radius: 18px; padding: 34px; box-shadow: var(--shadow); }
                .brand-mark { width: 52px; height: 52px; display: grid; place-items: center; border-radius: 14px; background: var(--brand); color: #fff; font-weight: 850; }
                .stack, .compose-form { display: grid; gap: 14px; margin-top: 22px; }
                label { display: grid; gap: 7px; font-weight: 650; font-size: 14px; }
                input, textarea, select { border: 1px solid var(--line); border-radius: 12px; padding: 11px 13px; font: inherit; background: #fff; color: var(--text); outline: none; }
                input:focus, textarea:focus, select:focus { border-color: var(--brand); box-shadow: 0 0 0 4px rgba(37, 99, 235, .12); }
                button, .button-link, .compose-button { border: 0; border-radius: 12px; padding: 10px 14px; font: inherit; font-weight: 750; cursor: pointer; display: inline-flex; justify-content: center; align-items: center; transition: transform .12s ease, background .12s ease, box-shadow .12s ease; }
                button:hover, .button-link:hover, .compose-button:hover { transform: translateY(-1px); box-shadow: 0 10px 20px rgba(15, 23, 42, .08); }
                .primary, .compose-button { background: var(--brand); color: #fff; }
                .primary:hover, .compose-button:hover { background: var(--brand-dark); }
                .secondary, .toolbar button { background: #eef2f7; color: var(--text); }
                .danger { background: var(--danger); color: #fff; }
                .danger-light { background: #fee2e2 !important; color: #991b1b !important; }
                .alert { margin: 16px 0; padding: 12px 14px; border-radius: 12px; font-weight: 650; }
                .alert.error { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
                .alert.success { background: #f0fdf4; color: var(--success); border: 1px solid #bbf7d0; }
                .instance-info { position: fixed; right: 14px; bottom: 10px; z-index: 5; padding: 5px 9px; border: 1px solid var(--line); border-radius: 999px; background: rgba(255,255,255,.92); color: var(--muted); font-size: 12px; font-weight: 700; box-shadow: 0 8px 18px rgba(15,23,42,.08); }
                .app-shell { min-height: 100vh; display: grid; grid-template-columns: 260px 1fr; }
                .sidebar { padding: 20px; background: #edf3fb; border-right: 1px solid var(--line); }
                .sidebar nav { margin-top: 22px; display: grid; gap: 5px; }
                .nav-item { padding: 11px 13px; border-radius: 12px; color: #344054; font-weight: 650; display: flex; justify-content: space-between; align-items: center; }
                .nav-item.active { background: #dbeafe; color: #1e40af; }
                .nav-item.disabled { opacity: .5; }
                .count { background: var(--brand); color: #fff; border-radius: 999px; padding: 2px 8px; font-size: 12px; }
                .app-main { min-width: 0; }
                .topbar { min-height: 76px; display: grid; grid-template-columns: 160px minmax(240px, 1fr) auto; align-items: center; gap: 18px; padding: 14px 22px; background: rgba(255,255,255,.9); border-bottom: 1px solid var(--line); position: sticky; top: 0; backdrop-filter: blur(10px); z-index: 2; }
                .app-name { font-weight: 850; color: var(--brand-dark); }
                .search-form { display: grid; grid-template-columns: minmax(160px, 1fr) 110px repeat(3, auto) auto; gap: 8px; align-items: center; }
                .search-form input { border-radius: 999px; background: #f8fafc; }
                .filter { display: inline-flex; grid-auto-flow: column; align-items: center; gap: 5px; color: var(--muted); font-weight: 650; white-space: nowrap; }
                .filter input { width: auto; }
                .search-button { background: var(--brand); color: #fff; }
                .account { display: flex; gap: 12px; align-items: center; color: var(--muted); font-weight: 650; }
                .account a { color: var(--brand-dark); }
                .mail-view { padding: 24px; }
                .content-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
                .toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 14px; }
                .select-all { display: inline-flex; grid-auto-flow: column; align-items: center; gap: 8px; padding: 8px 10px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
                .select-all input, .message-check { width: auto; }
                .message-list { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; box-shadow: var(--shadow); }
                .message-row { display: grid; grid-template-columns: 34px 28px 22px 190px 240px 104px 1fr 92px; gap: 12px; padding: 13px 16px; border-bottom: 1px solid var(--line); align-items: center; color: #475467; }
                .message-row:last-child { border-bottom: 0; }
                .message-row:hover { background: #f8fbff; }
                .message-row.unread { color: var(--text); font-weight: 800; background: #fff; }
                .sender, .subject, .preview { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .preview, .date { color: var(--muted); font-weight: 500; }
                .row-icon { color: #f59e0b; text-align: center; font-weight: 900; }
                .badge { background: var(--soft); color: #1e40af; padding: 4px 8px; border-radius: 999px; font-size: 12px; font-weight: 800; text-align: center; }
                .empty-state { background: var(--panel); border: 1px dashed #cbd5e1; border-radius: 16px; padding: 46px; text-align: center; color: var(--muted); }
                .message-actions { display: flex; justify-content: space-between; margin-bottom: 16px; }
                .message-card, .compose-card { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 24px; box-shadow: var(--shadow); }
                .message-title-line { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
                .message-meta { display: grid; gap: 7px; color: var(--muted); padding: 16px 0; border-bottom: 1px solid var(--line); }
                .message-body { white-space: pre-wrap; font: 15px/1.6 Inter, Segoe UI, Arial, sans-serif; margin: 22px 0 0; }
                .compose-shell { max-width: 900px; margin: 0 auto; width: 100%; }
                .compose-form input, .compose-form textarea { width: 100%; }
                .form-actions { display: flex; gap: 10px; align-items: center; }
                @media (max-width: 1100px) {
                    .topbar { grid-template-columns: 1fr; }
                    .search-form { grid-template-columns: 1fr 100px; }
                    .filter, .search-button { justify-self: start; }
                    .message-row { grid-template-columns: 30px 24px 20px 1fr 80px; }
                    .message-row .preview, .message-row .badge { display: none; }
                }
                @media (max-width: 760px) {
                    .app-shell { grid-template-columns: 1fr; }
                    .sidebar { display: flex; gap: 12px; overflow-x: auto; }
                    .sidebar nav { display: flex; margin-top: 0; }
                    .mail-view { padding: 16px; }
                    .message-row { grid-template-columns: 28px 22px 1fr; }
                    .message-row .row-icon:nth-of-type(2), .message-row .date, .message-row .sender { display: none; }
                }
                </style>
                """;
    }
}
