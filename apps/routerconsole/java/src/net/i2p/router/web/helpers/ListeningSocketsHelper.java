package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.util.InternalServerSocket;
import net.i2p.util.PortMapper;

/**
 * Renders the port mapper debug tab as a single consolidated table: the services
 * registered with the port mapper plus the in-JVM internal server sockets,
 * with a column flagging which ports are internal. Replaces the two separate
 * tables the page used to render, whose port-only socket list mapped numbers
 * to nothing.
 *
 * @since 0.9.71
 */
public class ListeningSocketsHelper {

    /**
     * Static helpers only, no instances.
     */
    private ListeningSocketsHelper() {}

    private static final String IN_JVM = "in-JVM";
    private static final String UNLABELLED = "internal";

    /** Row order for the table: port (column 2) ascending, then service label (column 0). */
    private static final Comparator<String[]> ROW_ORDER =
        Comparator.comparingInt((String[] r) -> Integer.parseInt(r[2])).thenComparing(r -> r[0]);

    /**
     * Build the table rows, sorted by port ascending, then service. Services
     * whose port is also a claimed internal socket are flagged. Internal
     * sockets not covered by any service get their own row, labelled from the
     * socket or falling back to "internal".
     * Pure, for unit tests.
     *
     * @param services service name to bound address, from {@link PortMapper#getForwardedServices}
     * @param internalPorts internal port to optional label, from {@link InternalServerSocket#getPortLabels}
     * @return rows of {service, host, port, internal}, never null
     */
    static List<String[]> buildRows(Map<String, InetSocketAddress> services, Map<Integer, String> internalPorts) {
        List<String[]> rows = new ArrayList<>(services.size() + internalPorts.size());
        Set<Integer> covered = new TreeSet<>();
        for (Map.Entry<String, InetSocketAddress> e : services.entrySet()) {
            InetSocketAddress ia = e.getValue();
            if (ia == null) {continue;}
            boolean internal = internalPorts.containsKey(ia.getPort());
            if (internal) {covered.add(ia.getPort());}
            rows.add(new String[] {e.getKey(), PortMapper.displayHost(ia.getHostName()),
                     Integer.toString(ia.getPort()), internal ?
                     "<span class=yes>yes</span>" :
                     "<span class=no>no</span>"});
        }
        for (Map.Entry<Integer, String> e : internalPorts.entrySet()) {
            if (covered.contains(e.getKey())) {continue;}
            String label = e.getValue() != null ? e.getValue() : UNLABELLED;
            rows.add(new String[] {label, IN_JVM, Integer.toString(e.getKey()), "<span class=yes>yes</span>"});
        }
        rows.sort(ROW_ORDER);
        return rows;
    }

    /**
     * Render the consolidated table, followed by the registered eepsites as a
     * separator block as the port mapper always showed them.
     *
     * @param pm the router's port mapper, may be a dummy but not null
     * @param out where to write
     * @throws IOException if writing to out fails
     */
    public static void render(PortMapper pm, Writer out) throws IOException {
        List<String[]> rows = buildRows(pm.getForwardedServices(), InternalServerSocket.getPortLabels());
        out.write("<h2 id=debug_listening>Listening Services</h2>\n<table id=listening>\n<thead>\n" +
                  "<tr><th>Service</th><th>Host</th><th>Port</th><th>Internal</th></tr>\n</thead>\n");
        for (String[] row : rows) {
            out.write("<tr><td>" + DataHelper.escapeHTML(row[0]) + "</td><td>" + row[1] + "</td><td>" + row[2] + "</td><td>" + row[3] + "</td></tr>\n");
        }
        Set<String> eepsites = pm.getEepsites();
        if (!eepsites.isEmpty()) {
            out.write("<tr class=separator><td colspan=4></tr>\n<tr><th>Eepsite<th>URL<th><th>\n");
            for (String url : new TreeSet<>(eepsites)) {
                String s = url.startsWith("https://") ? PortMapper.SVC_HTTPS_EEPSITE : PortMapper.SVC_EEPSITE;
                out.write("<tr><td>" + s + "<td><a href=\"" + url + "\">" + url + "</a><td><td>\n");
            }
        }
        out.write("</table>\n");
    }
}
