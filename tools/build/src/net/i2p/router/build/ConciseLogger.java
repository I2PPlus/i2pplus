package net.i2p.router.build;

import java.io.PrintStream;
import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;

public class ConciseLogger extends DefaultLogger {
    private final boolean color;
    private static final String RST = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[1;31m";
    private static final String DARK_RED = "\033[31m";

    private boolean targetHeaderPending;
    private BuildEvent lastTargetEvent;
    private int deprecationPhase; // 0=none, 1=source line next, 2=caret line next
    private String deprecationSource;
    private boolean pendingWarningLine;

    public ConciseLogger() {
        this.color = System.console() != null;
    }

    private String c(String code, String text) {
        return color ? code + text + RST : text;
    }

    @Override
    public void targetStarted(BuildEvent event) {
        String name = event.getTarget().getName();
        if (name != null && !name.isEmpty()) {
            targetHeaderPending = true;
            lastTargetEvent = event;
        }
    }

    @Override
    public void targetFinished(BuildEvent event) {
        targetHeaderPending = false;
        lastTargetEvent = null;
    }

    private boolean isSuppressible(BuildEvent event) {
        String msg = event.getMessage();
        if (msg == null || event.getTask() == null)
            return false;
        String taskName = event.getTask().getTaskName();
        if ("java".equals(taskName)) {
            return msg.contains(":INFO::main: Logging initialized")
                || msg.contains(":INFO:oajs.TldScanner:")
                || msg.contains(":INFO:oaj.JspC:");
        }
        if ("javac".equals(taskName) && (msg.startsWith("Creating empty") || msg.startsWith("Ignoring source, target") || msg.contains("ignoring it") || msg.startsWith("Note:")))
            return true;
        if ("exec".equals(taskName) && msg.startsWith("Note:"))
            return true;
        if ("exec".equals(taskName) && msg.startsWith("Generating ") && msg.contains(" ResourceBundle"))
            return true;
        if ("exec".equals(taskName) && msg.contains("Using cached translation bundles"))
            return true;
        if ("jar".equals(taskName) && (msg.contains("module-info.class already added") || msg.contains("already added, skipping")))
            return true;
        if ("loadfile".equals(taskName) && msg.contains("doesn't exist"))
            return true;
        if ("touch".equals(taskName) && msg.startsWith("Creating"))
            return true;
        return ("copy".equals(taskName) && (msg.startsWith("Copying") || msg.startsWith("Copied")))
            || ("replace".equals(taskName) && msg.startsWith("Replaced"))
            || ("mkdir".equals(taskName) && msg.startsWith("Created dir"))
            || (("izpack".equals(taskName) || "izpack5".equals(taskName))
                && (msg.startsWith("Copying ") || msg.startsWith("Merging ") || msg.startsWith("Writing ")))
            || ("launch4j".equals(taskName)
                && ("Linking".equals(msg) || "Wrapping".equals(msg) || msg.startsWith("WARNING:")));
    }

    private String taskPrefix(String taskName) {
        int pad = 12 - taskName.length() - 3;
        StringBuilder sb = new StringBuilder(12);
        while (sb.length() < pad) sb.append(' ');
        sb.append('[').append(taskName).append("] ");
        return sb.toString();
    }

    @Override
    public void messageLogged(BuildEvent event) {
        if (targetHeaderPending) {
            if (isSuppressible(event))
                return;
            if (event.getPriority() <= msgOutputLevel) {
                if (Project.MSG_INFO <= msgOutputLevel && !emacsMode)
                    printMessage(c(CYAN, " \u2022 " + lastTargetEvent.getTarget().getName() + ":"), out, Project.MSG_INFO);
                targetHeaderPending = false;
            }
        } else if (isSuppressible(event)) {
            return;
        }
        String taskName = event.getTask() != null ? event.getTask().getTaskName() : null;
        String msg = event.getMessage();
        if (msg != null && event.getPriority() <= msgOutputLevel) {
            if ("echo".equals(taskName)) {
                PrintStream stream = event.getPriority() == Project.MSG_ERR ? err : out;
                String color = event.getPriority() == Project.MSG_ERR ? RED : GREEN;
                stream.println(c(color, "      * " + (emacsMode ? msg : msg.trim())));
                stream.flush();
                return;
            }
            if ("exec".equals(taskName) && (msg.startsWith("*") || msg.matches("^\\S+\\.\\w+:\\s+[0-9a-f]{40,}$"))) {
                PrintStream stream = out;
                String outMsg = msg.startsWith("*") ? msg : "* " + msg;
                stream.println(c(GREEN, "      " + (emacsMode ? outMsg : outMsg.trim())));
                stream.flush();
                return;
            }
            if (event.getPriority() == Project.MSG_ERR) {
                PrintStream stream = err;
                String prefix = taskName != null ? taskPrefix(taskName) : "";
                stream.println(c(RED, prefix + (emacsMode ? msg : msg.trim())));
                stream.flush();
                return;
            }
            if (event.getPriority() == Project.MSG_WARN && msg.contains("warning:")) {
                PrintStream stream = out;
                String prefix = taskName != null ? taskPrefix(taskName) : "";
                if ("javac".equals(taskName)) {
                    int idx = msg.indexOf(": warning: ");
                    if (idx >= 0) {
                        msg = "WARNING: " + msg.substring(idx + ": warning: ".length());
                    } else if (msg.startsWith("warning: ")) {
                        msg = "WARNING: " + msg.substring("warning: ".length());
                    }
                    if (msg.contains("[deprecation]")) {
                        deprecationSource = msg;
                        deprecationPhase = 1;
                        stream.flush();
                        return;
                    }
                }
                stream.println(c(DARK_RED, prefix + (emacsMode ? msg : msg.trim())));
                stream.flush();
                return;
            }

        }
        if ("javac".equals(taskName) && deprecationPhase > 0 && msg != null) {
            if (deprecationPhase == 1) {
                String trimmed = msg.trim().replaceAll(" ?\\{?$", "");
                String condensed = deprecationSource.replace(" has been deprecated", " -> " + trimmed);
                PrintStream stream = out;
                stream.println(c(DARK_RED, "      * " + (emacsMode ? condensed : condensed.trim())));
                stream.flush();
                deprecationPhase = 2;
                return;
            }
            deprecationPhase = 0;
            deprecationSource = null;
            return;
        }
        if (msg != null && msg.contains("WARNING")) {
            PrintStream stream = out;
            String cleaned = msg.trim().replaceFirst("^[\\*\\s]+", "");
            stream.println(c(DARK_RED, "      * " + (emacsMode ? cleaned : cleaned)));
            stream.flush();
            pendingWarningLine = true;
            return;
        }
        if (pendingWarningLine) {
            pendingWarningLine = false;
            if (msg != null) {
                PrintStream stream = out;
                stream.println(c(DARK_RED, "      * " + (emacsMode ? msg : msg.trim())));
                stream.flush();
                return;
            }
        }
        super.messageLogged(event);
    }

    @Override
    public void buildFinished(BuildEvent event) {
        if (event.getException() != null) {
            String msg = event.getMessage();
            if (msg != null) {
                printMessage(c(RED, "\n" + msg), err, Project.MSG_ERR);
                Throwable ex = event.getException();
                while (ex != null) {
                    String m = ex.getMessage();
                    if (m != null)
                        printMessage(c(RED, m), err, Project.MSG_ERR);
                    ex = ex.getCause();
                }
                return;
            }
        }
        super.buildFinished(event);
    }
}
