package net.i2p.router.build;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;

public class ConciseLogger extends DefaultLogger {
    private boolean targetHeaderPending;
    private BuildEvent lastTargetEvent;

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
        if ("jar".equals(taskName) && msg.contains("module-info.class already added"))
            return true;
        return ("copy".equals(taskName) && (msg.startsWith("Copying") || msg.startsWith("Copied")))
            || ("replace".equals(taskName) && msg.startsWith("Replaced"))
            || ("mkdir".equals(taskName) && msg.startsWith("Created dir"))
            || (("izpack".equals(taskName) || "izpack5".equals(taskName))
                && (msg.startsWith("Copying ") || msg.startsWith("Merging ") || msg.startsWith("Writing ")))
            || ("launch4j".equals(taskName)
                && ("Linking".equals(msg) || "Wrapping".equals(msg) || msg.startsWith("WARNING:")));
    }

    @Override
    public void messageLogged(BuildEvent event) {
        if (targetHeaderPending) {
            if (isSuppressible(event))
                return;
            if (event.getPriority() <= msgOutputLevel) {
                if (Project.MSG_INFO <= msgOutputLevel && !emacsMode)
                    super.targetStarted(lastTargetEvent);
                targetHeaderPending = false;
            }
        } else if (isSuppressible(event)) {
            return;
        }
        super.messageLogged(event);
    }
}
