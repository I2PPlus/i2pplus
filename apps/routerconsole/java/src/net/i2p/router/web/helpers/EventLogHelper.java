package net.i2p.router.web.helpers;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.util.EventLog;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.HelperBase;

/**
 *  /events.jsp
 */
public class EventLogHelper extends FormHandler {
    private long _from, _age;
    //private long _to = Long.MAX_VALUE;
    private String _event = ALL;
    // EventLog name to translated display string
    private final Map<String, String> _xevents;

    private static final String ALL = "all";
    private static final String[] _events = new String[] {
        EventLog.ABORTED, _x("Aborted startup"),
        EventLog.BECAME_FLOODFILL, _x("Enabled floodfill"),
        EventLog.CHANGE_IP, _x("Changed IP"),
        EventLog.CHANGE_PORT, _x("Changed port"),
        EventLog.CLOCK_SHIFT, _x("Clock shifted"),
        EventLog.CRASHED, _x("Crashed"),
        EventLog.CRITICAL, _x("Critical error"),
        EventLog.DEADLOCK, _x("Deadlock detected"),
        EventLog.INSTALLED, _x("Installed new version"),
        EventLog.INSTALL_FAILED, _x("Install failed"),
        EventLog.NETWORK, _x("Network error"),
        EventLog.NEW_IDENT, _x("New router identity"),
        EventLog.NOT_FLOODFILL, _x("Disabled floodfill"),
        EventLog.OOM, _x("Out of memory error"),
        EventLog.REACHABILITY, _x("Reachability change"),
        EventLog.REKEYED, _x("New router identity"),
        EventLog.RESEED, _x("Reseeded router"),
        EventLog.SOFT_RESTART, _x("Soft restart"),
        EventLog.STARTED, _x("Started router"),
        EventLog.STOPPED, _x("Stopped router"),
        EventLog.UPDATED, _x("Updated router"),
        EventLog.WATCHDOG, _x("Watchdog warning")
    };
    // in seconds
    private static final long DAY = 24*60*60L;
    private static final long[] _times = { 0, DAY, 7*DAY, 30*DAY, 90*DAY, 365*DAY };

    public EventLogHelper() {
        super();
        _xevents = new HashMap<String, String>(1 + (_events.length / 2));
    }

    protected void processForm() {}

    /** set the defaults after we have a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        for (int i = 0; i < _events.length; i += 2) {
            _xevents.put(_events[i], _t(_events[i + 1]));
        }
    }

    public void setFrom(String s) {
        try {
            _age = Long.parseLong(s) * 1000;
            if (_age > 0)
                _from = _context.clock().now() - _age;
            else
                _from = 0;
        } catch (NumberFormatException nfe) {
            _age = 0;
            _from = 0;
        }
    }

    //public void setTo(String s) {
    //   _to = s;
    //}

    public void setType(String s) {
        _event = s;
    }

    public String getForm() {
        // too hard to use the standard formhandler.jsi / FormHandler.java session nonces
        // since graphs.jsp needs the refresh value in its <head>.
        // So just use the "shared/console nonce".
        String nonce = CSSHelper.getNonce();
        try {
            _out.write("<br><h3 id=displayevents>" + _t("Display Events") + "</h3>");
            _out.write("<form action=\"events\" method=post>\n" +
                       "<input type=hidden name=\"action\" value=\"Save\">\n" +
                       "<input type=hidden name=\"nonce\" value=\"" + nonce + "\" >\n<b>");
            _out.write(_t("Events since") + ":</b> <select name=\"from\">");
            for (int i = 0; i < _times.length; i++) {
                writeOption(_times[i]);
            }
            _out.write("</select>&nbsp;<b>");
            _out.write(_t("Event type") + ":</b> <select name=\"type\">");
            // sorted by translated display string
            Map<String, String> events = new TreeMap<String, String>(Collator.getInstance());
            for (int i = 0; i < _events.length; i += 2) {
                events.put(_xevents.get(_events[i]), _events[i]);
            }
            writeOption(_t("All events"), ALL);
            for (Map.Entry<String, String> e : events.entrySet()) {
                writeOption(e.getKey(), e.getValue());
            }
            _out.write("</select>" +
                       "&nbsp;<input type=submit class=accept value=\"" + _t("Filter events") + "\"></form>");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    private void writeOption(String key, String val) throws IOException {
         _out.write("<option value=\"");
         _out.write(val);
         _out.write("\"");
         if (val.equals(_event))
             _out.write(HelperBase.SELECTED);
         _out.write(">");
         _out.write(key);
         _out.write("</option>\n");
    }

    /**
     * @param age seconds
     */
    private void writeOption(long age) throws IOException {
         _out.write("<option value=\"");
         _out.write(Long.toString(age));
         _out.write("\"");
         if (age == _age / 1000)
             _out.write(HelperBase.SELECTED);
         _out.write(">");
         if (age == 0)
             _out.write(_t("All events"));
         else
             _out.write(DataHelper.formatDuration2(age * 1000));
         _out.write("</option>\n");
    }

    public String getEvents() {
        EventLog ev = _context.router().eventLog();
        // oldest first
        Map<Long, String> events;
        boolean isAll = ALL.equals(_event);
        if (isAll)
            events = ev.getEvents(_from);
        else
            events = ev.getEvents(_event, _from);
        String xev = _xevents.get(_event);
        if (xev == null)
            xev = _event;
        xev = DataHelper.escapeHTML(xev);
        if (events.isEmpty()) {
            if (isAll) {
                if (_age == 0)
                    return ("<table id=eventlog>\n<tr><td class=infohelp>") + _t("No events found") + ("</td></tr></table>");
                return ("<table id=eventlog><tr><td>") + _t("No events found in previous {0}", DataHelper.formatDuration2(_age)) + ("</td></tr></table>\n");
            }
            if (_age == 0)
                return ("<table id=eventlog data-sortable>\n<tr><td  class=infohelp>") + _t("No \"{0}\" events found", xev) + ("</td></tr></table>\n");
            return ("<table id=eventlog>\n<tr><td class=infohelp>") +
                    _t("No \"{0}\" events found in previous {1}", xev, DataHelper.formatDuration2(_age)) + ("</td></tr></table>\n");
        }
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<table id=eventlog><thead><tr><th>");
        buf.append(_t("Time"));
        buf.append("</th><th>");
        if (isAll) {
            buf.append(_t("Event"));
            buf.append("</th><th>");
            buf.append(_t("Details"));
        } else {
            buf.append(xev);
        }
        buf.append("</th></tr></thead>\n");

        List<Map.Entry<Long, String>> entries = new ArrayList<Map.Entry<Long, String>>(events.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<Long, String> e : entries) {
            long time = e.getKey().longValue();
            String event = e.getValue();
            String type = event;
            while (type.length() < 8) {
                type = type + (' ');
            }
            // create a class from truncated event type so we can style the tr's by event severity
            type = type.substring(0,8).replaceAll(" .+$", "").replaceAll("\\d", "").toLowerCase();
            if (isAll) {
                buf.append("<tr class=\"").append(type).append(" lazy\">");
            } else {
                buf.append("<tr>");
            }
            buf.append("<td>");
            buf.append(DataHelper.formatTime(time).replace("-", " "));
            buf.append("</td><td>");
            if (isAll) {
                 String[] s = DataHelper.split(event, " ", 2);
                 String xs = _xevents.get(s[0]);
                 if (xs == null)
                     xs = s[0];
                 buf.append(xs);
                 buf.append("</td><td>");
                 if (s.length > 1)
                     buf.append(s[1]);
            } else {
                 buf.append(event);
            }
            buf.append("</td></tr>\n");
        }
        buf.append("</table>\n");
        return buf.toString();
    }
}
