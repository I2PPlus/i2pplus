/*
 * This file is part of SusDNS project for I2P
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/**
 *  Talk to the NamingService API instead of modifying the hosts.txt files directly,
 *  except for the 'published' addressbook.
 *
 *  @since 0.8.7
 */
public class NamingServiceBean extends AddressbookBean {
    private static final Log _log = new Log(NamingServiceBean.class);
    private static final String DEFAULT_NS = "BlockfileNamingService";
    private String detail;
    private String notes;
    private boolean fail = false;

    private boolean isDirect() {return getBook().equals("published");}

    @Override
    protected boolean isPrefiltered() {
        if (isDirect()) {return super.isPrefiltered();}
        return (search == null || search.length() <= 0) &&
               (filter == null || filter.length() <= 0) &&
               getNamingService().getName().equals(DEFAULT_NS);
    }

    @Override
    protected int resultSize() {
        if (isDirect()) {return super.resultSize();}
        return isPrefiltered() ? totalSize() : entries.length;
    }

    @Override
    protected int totalSize() {
        if (isDirect()) {return super.totalSize();}
        // only blockfile needs the list property
        Properties props = new Properties();
        props.setProperty("list", getFileName());
        return getNamingService().size(props);
    }

    @Override
    public boolean isNotEmpty() {
        if (isDirect()) {return super.isNotEmpty();}
        return totalSize() > 0;
    }

    @Override
    public String getFileName() {
        if (isDirect()) {return super.getFileName();}
        loadConfig();
        String filename = properties.getProperty(getBook() + "_addressbook");
        if (filename == null) {return getBook();}
        return basename(filename);
    }

    @Override
    public String getDisplayName() {
        if (isDirect()) {return super.getDisplayName();}
        loadConfig();
        return _t("{0} address book in {1} database", getFileName(), getNamingService().getName());
    }

    /** depth-first search */
    private static NamingService searchNamingService(NamingService ns, String srch) {
        String name = ns.getName();
        if (name.equals(srch) || basename(name).equals(srch) || name.equals(DEFAULT_NS)) {return ns;}
        List<NamingService> list = ns.getNamingServices();
        if (list != null) {
            for (NamingService nss : list) {
                NamingService rv = searchNamingService(nss, srch);
                if (rv != null) {return rv;}
            }
        }
        return null;
    }

    private static String basename(String filename) {
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {filename = filename.substring(slash + 1);}
        return filename;
    }

    /** @return the NamingService for the current file name, or the root NamingService */
    private NamingService getNamingService() {
        NamingService root = _context.namingService();
        NamingService rv = searchNamingService(root, getFileName());
        return rv != null ? rv : root;
    }

    /**
     *  Load addressbook and apply filter, returning messages about this.
     *  To control memory, don't load the whole addressbook if we can help it...
     *  only load what is searched for.
     */
    @Override
    public String getLoadBookMessages() {
        if (isDirect()) {return super.getLoadBookMessages();}
        NamingService service = getNamingService();
        debug("[" + service + "] Performing search for: '" + search + "' with filter: '" + filter + "'");
        String message = "";
        try {
            LinkedList<AddressBean> list = new LinkedList<AddressBean>();
            Map<String, Destination> results;
            boolean sortByDate = "latest".equals(filter);
            Properties searchProps = new Properties();
            searchProps.setProperty("list", getFileName()); // only blockfile needs this
            if (filter != null && !sortByDate && !filter.equals("alive") && !filter.equals("dead")) {
                String startsAt = filter.equals("0-9") ? "[0-9]" : filter;
                searchProps.setProperty("startsWith", startsAt);
            }
            if (isPrefiltered()) {
                // Only limit if we not searching or filtering, so we will know the total number of results
                if (beginIndex > 0) {searchProps.setProperty("skip", Integer.toString(beginIndex));}
                int limit = 1 + endIndex - beginIndex;
                if (limit > 0) {searchProps.setProperty("limit", Integer.toString(limit));}
            }

            Hash reverse = null;
            if (search != null && search.length() > 0) {
                if (search.length() == 60 && search.endsWith(".b32.i2p")) {
                    byte[] b = Base32.decode(search.substring(0,52));
                    if (b != null) {reverse = new Hash(b);}
                } else if (search.length() >= 516) {
                    byte[] b = Base64.decode(search);
                    if (b != null) {reverse = _context.sha().calculateHash(b);}
                }
                if (reverse == null) {
                    searchProps.setProperty("search", search.toLowerCase(Locale.US));
                }
            }
            if (reverse != null) {
                List<String> names = service.reverseLookupAll(reverse);
                if (names != null) {
                    results = new HashMap<String, Destination>(names.size());
                    for (String name : names) {
                        Destination d = service.lookup(name, searchProps, null);
                        if (d != null) {results.put(name, d);}
                    }
                } else {results = Collections.emptyMap();}
            } else {results = service.getEntries(searchProps);}

            debug("Results returned by search: " + results.size());
            int blacklistedHosts = 0;
            for (Map.Entry<String, Destination> entry : results.entrySet()) {
                String name = entry.getKey();
                if (filter != null && filter.length() > 0 && !sortByDate) {
                    if (filter.equals("0-9")) {
                        char first = name.charAt(0);
                        if (first < '0' || first > '9') {continue;}
                    }
                    else if (filter.equals("alive")) {
                        // Check if host is alive using cached ping results from HostCheckerBridge
                        boolean isAlive = false;
                        try {
                            java.util.Map<String, net.i2p.addressbook.HostChecker.PingResult> allResults = net.i2p.addressbook.HostCheckerBridge.getAllPingResults();
                            if (allResults != null) {
                                net.i2p.addressbook.HostChecker.PingResult pingResult = allResults.get(name);
                                if (pingResult != null && pingResult.reachable) {
                                    isAlive = true;
                                }
                            }
                        } catch (Exception e) {
                            // If we can't get status, skip this host for alive filter
                            continue;
                        }
                        if (!isAlive) {continue;}
                    }
                    else if (filter.equals("dead")) {
                        // Check if host is dead using cached ping results from HostCheckerBridge
                        boolean isDead = false;
                        try {
                            java.util.Map<String, net.i2p.addressbook.HostChecker.PingResult> allResults = net.i2p.addressbook.HostCheckerBridge.getAllPingResults();
                            if (allResults != null) {
                                net.i2p.addressbook.HostChecker.PingResult pingResult = allResults.get(name);
                                if (pingResult != null && !pingResult.reachable) {
                                    isDead = true;
                                }
                            }
                        } catch (Exception e) {
                            // If we can't get status, skip this host for dead filter
                            continue;
                        }
                        if (!isDead) {continue;}
                    }
                    else if (! name.toLowerCase(Locale.US).startsWith(filter.toLowerCase(Locale.US))) {
                        continue;
                    }
                }
                if (search != null && search.length() > 0 && reverse == null) {
                    if (name.indexOf(search) == -1) {continue;}
                }

                // Check if host is blacklisted
                BlacklistBean blacklist = new BlacklistBean();
                boolean isBlacklisted = blacklist.isBlacklistedByAnyForm(name);
                if (isBlacklisted) {
                    //_log.warn("Filtering out blacklisted host: " + name);
                    blacklistedHosts++;
                    continue;
                }

                String destination = entry.getValue().toBase64();
                AddressBean bean = new AddressBean(name, entry.getValue());
                Properties p = new Properties();
                Destination d = service.lookup(name, searchProps, p);
                if (d != null) {bean.setProperties(p);}
                list.addLast(bean);
            }
            AddressBean array[] = list.toArray(new AddressBean[list.size()]);
            if (sortByDate) {Arrays.sort(array, new AddressByDateSorter());}
            else if (!(results instanceof SortedMap)) {Arrays.sort(array, sorter);}
            entries = array;
            _log.debug("Final entries count after filtering: " + (entries != null ? entries.length : 0) +
                       (blacklistedHosts > 0 ? " (Blacklisted: " + blacklistedHosts + ")" : ""));
            message = generateLoadMessage();
        } catch (RuntimeException e) {warn(e);}
        if (message.length() > 0) {
            if (filter != null && filter.length() > 0) {
                message = "<span id=filtered>" + message; // span closed in AddressbookBean
            } else {message = "<span id=showing>" + message;}
        }
        return message;
    }

    @Override
    public AddressBean[] getEntries() {
        // Force reload of entries to ensure blacklist filtering is applied
        getLoadBookMessages();
        return entries;
    }

    /** Perform actions, returning messages about this. */
    @Override
    public String getMessages() {
        if (isDirect()) {return super.getMessages();}
        // Loading config and addressbook moved into getLoadBookMessages()
        String message = "";

        if (action != null) {
            Properties nsOptions = new Properties();
            // only blockfile needs this
            nsOptions.setProperty("list", getFileName());
            if ("POST".equals(method) && (_context.getBooleanProperty(PROP_PW_ENABLE) ||
                (serial != null && serial.equals(lastSerial)))) {
                boolean changed = false;
                if (action.equals(_t("Add")) || action.equals(_t("Replace")) || action.equals(_t("Add Alternate"))) {
                    if (hostname != null && destination != null) {
                        try {
                            // throws IAE with translated message
                            String host = AddressBean.toASCII(hostname);
                            String displayHost = host.equals(hostname) ? hostname : hostname + " (" + host + ')';

                            Properties outProperties= new Properties();
                            Destination oldDest = getNamingService().lookup(host, nsOptions, outProperties);
                            if (oldDest != null && destination.equals(oldDest.toBase64())) {
                                message = _t("Host name {0} is already in address book, unchanged.", displayHost);
                            } else if (oldDest == null && action.equals(_t("Add Alternate"))) {
                                message = _t("Host name {0} is not in the address book.", displayHost);
                            } else if (oldDest != null && action.equals(_t("Add"))) {
                                message = _t("Host name {0} is already in address book with a different destination. Click \"Replace\" to overwrite.", displayHost);
                            } else {
                                boolean wasB32 = false;
                                try {
                                    Destination dest;
                                    if (destination.length() >= 516) {dest = new Destination(destination);}
                                    else if (destination.contains(".b32.i2p")) {
                                        wasB32 = true;
                                        if (destination.startsWith("http://") || destination.startsWith("https://")) {
                                            // do them a favor, pull b32 out of pasted URL
                                            try {
                                                URI uri = new URI(destination);
                                                String b32 = uri.getHost();
                                                if (b32 == null || !b32.endsWith(".b32.i2p") || b32.length() < 60) {
                                                    throw new DataFormatException("");
                                                }
                                                dest = _context.namingService().lookup(b32);
                                                if (dest == null) {
                                                    throw new DataFormatException(_t("Unable to resolve Base 32 address"));
                                                }
                                            } catch(URISyntaxException use) {throw new DataFormatException("");}
                                        } else if (destination.endsWith(".b32.i2p") && destination.length() >= 60) {
                                            dest = _context.namingService().lookup(destination);
                                            if (dest == null) {
                                                throw new DataFormatException(_t("Unable to resolve Base 32 address"));
                                            }
                                        } else {throw new DataFormatException("");}
                                        fail =true;
                                    } else {throw new DataFormatException("");}
                                    if (oldDest != null) {
                                        nsOptions.putAll(outProperties);
                                        String now = Long.toString(_context.clock().now());
                                        if (action.equals(_t("Add Alternate"))) {nsOptions.setProperty("a", now);}
                                        else {nsOptions.setProperty("m", now);}
                                    }
                                    nsOptions.setProperty("s", _t("Manually added via SusiDNS"));
                                    boolean success;
                                    if (action.equals(_t("Add Alternate"))) {
                                        // check all for dups
                                        List<Destination> all = getNamingService().lookupAll(host);
                                        if (all == null || dest == null || !all.contains(dest)) {
                                            success = getNamingService().addDestination(host, dest, nsOptions);
                                        } else {success = false;} // will get generic message below
                                    } else {success = getNamingService().put(host, dest, nsOptions);}
                                    if (success) {
                                        changed = true;
                                        if (oldDest == null || action.equals(_t("Add Alternate"))) {
                                            message = _t("Destination added for {0}.", displayHost);
                                        } else {
                                            message = _t("Destination changed for {0}.", displayHost);
                                        }
                                        if (!host.endsWith(".i2p")) {
                                            message += "<br>" + _t("Warning - host name does not end with \".i2p\"");
                                        }
                                        // clear form
                                        hostname = null;
                                        destination = null;
                                    } else {
                                        message = _t("Failed to add Destination for {0} to naming service {1}", displayHost, getNamingService().getName()) + "<br>";
                                        fail = true;
                                    }
                                } catch (DataFormatException dfe) {
                                    String msg = dfe.getMessage();
                                    if (msg != null && msg.length() > 0) {message = msg;}
                                    else if (wasB32) {message = _t("Invalid Base 32 host name.");}
                                    else {message = _t("Invalid Base 64 destination.");}
                                    fail = true;
                                }
                            }
                        } catch (IllegalArgumentException iae) {
                            message = iae.getMessage();
                            if (message == null) {message = _t("Invalid host name \"{0}\".", hostname);}
                            fail = true;
                        }
                    } else {message = _t("Please enter a host name and destination");}
                    // clear search when adding
                    search = null;
                } else if (action.equals(_t("Delete Selected")) || action.equals(_t("Delete Entry"))) {
                    String name = null;
                    int deleted = 0;
                    Destination matchDest = null;
                    if (action.equals(_t("Delete Entry"))) {
                        // remove specified dest only in case there is more than one
                        if (destination != null) {
                            try {matchDest = new Destination(destination);}
                            catch (DataFormatException dfe) {}
                        }
                    }
                    for (String n : deletionMarks) {
                        boolean success;
                        if (matchDest != null) {success = getNamingService().remove(n, matchDest, nsOptions);}
                        else {success = getNamingService().remove(n, nsOptions);}
                        String uni = AddressBean.toUnicode(n);
                        String displayHost = uni.equals(n) ? n :  uni + " (" + n + ')';
                        if (!success) {
                            message += _t("Failed to delete Destination for {0} from naming service {1}", displayHost, getNamingService().getName()) + "<br>";
                            fail = true;
                        } else if (deleted++ == 0) {
                            changed = true;
                            name = displayHost;
                        }
                    }
                    if (changed) {
                        if (deleted == 1) {message += _t("Destination {0} deleted.", name);} // parameter is a host name
                        else {message = ngettext("1 destination deleted.", "{0} destinations deleted.", deleted);} // parameter will always be >= 2
                    } else {message = _t("No valid entries selected to delete.");}
                    if (action.equals(_t("Delete Entry"))) {search = null;} // clear search when deleting
                } else if (action.equals(_t("Blacklist Selected"))) {
                    List<String> hostsToBlacklist = new ArrayList<String>();
                    for (String n : deletionMarks) {
                        hostsToBlacklist.add(n);
                    }
                    BlacklistBean blacklist = new BlacklistBean();
                    int added = blacklist.addEntries(hostsToBlacklist);
                    if (added > 0) {
                        message = ngettext("1 destination blacklisted.", "{0} destinations blacklisted.", added);
                    } else {
                        message = _t("No valid entries selected to blacklist.");
                        fail = true;
                    }
                }
                if (changed) {
                    message += "<br>" + _t("Address book saved.");
                }
            }
            else {
                fail = true;
                message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.") +
                          ' ' + _t("If the problem persists, verify that you have cookies enabled in your browser.");
            }
        }

        action = null;

        if (message.length() > 0) {message = styleMessage(message, fail);}
        return message;
    }

    /**
     *  @since 0.9.35
     */
        public void saveNotes() {
        if (action == null || !action.equals(_t("Save Notes")) ||
            destination == null || detail == null || isDirect() ||
            serial == null || !serial.equals(lastSerial)) {
            return;
        }
        Properties nsOptions = new Properties();
        List<Properties> propsList = new ArrayList<Properties>(4);
        nsOptions.setProperty("list", getFileName());
        List<Destination> dests = getNamingService().lookupAll(detail, nsOptions, propsList);
        if (dests == null) {return;}
        for (int i = 0; i < dests.size(); i++) {
            if (!dests.get(i).toBase64().equals(destination)) {continue;}
            Properties props = propsList.get(i);
            if (notes != null && notes.length() > 0) {
                byte[] nbytes = DataHelper.getUTF8(notes);
                    if (nbytes.length > 255) { // violently truncate, possibly splitting a char
                    byte[] newbytes = new byte[255];
                    System.arraycopy(nbytes, 0, newbytes, 0, 255);
                    notes = DataHelper.getUTF8(newbytes);
                    // drop replacement char or split pair
                    int last = notes.length() - 1;
                    char lastc = notes.charAt(last);
                    if (lastc == (char) 0xfffd || Character.isHighSurrogate(lastc)) {
                        notes = notes.substring(0, last);
                    }
                }
                props.setProperty("notes", notes);
            } else {props.setProperty("notes", "");}

            props.setProperty("list", getFileName());
            String now = Long.toString(_context.clock().now());
            props.setProperty("m", now);
            if (dests.size() > 1) {
                // we don't have any API to update properties on a single dest
                // so remove and re-add
                getNamingService().remove(detail, dests.get(i), nsOptions);
                getNamingService().addDestination(detail, dests.get(i), props);
            } else {getNamingService().put(detail, dests.get(i), props);}
            return;
        }
    }

    public void setH(String h) {this.detail = DataHelper.stripHTML(h);}

    /**
     *  @since 0.9.35
     */
    public void setNofilter_notes(String n) {notes = n;}

    public AddressBean getLookup() {
        if (this.detail == null) {return null;}
        if (isDirect()) {
            // go to some trouble to make this work for the published addressbook
            this.filter = this.detail.substring(0, 1);
            this.search = this.detail;
            // we don't want the messages, we just want to populate entries
            super.getLoadBookMessages();
            for (int i = 0; i < this.entries.length; i++) {
                if (this.search.equals(this.entries[i].getName())) {return this.entries[i];}
            }
            return null;
        }
        Properties nsOptions = new Properties();
        Properties outProps = new Properties();
        nsOptions.setProperty("list", getFileName());
        Destination dest = getNamingService().lookup(this.detail, nsOptions, outProps);
        if (dest == null) {return null;}
        AddressBean rv = new AddressBean(this.detail, dest);
        rv.setProperties(outProps);
        return rv;
    }

    /**
     *  @since 0.9.26
     */
    public List<AddressBean> getLookupAll() {
        if (this.detail == null) {return null;}
        if (isDirect()) {
            AddressBean ab = getLookup(); // won't work for the published addressbook
            if (ab != null) {
                // Check if host is blacklisted
                BlacklistBean blacklist = new BlacklistBean();
                if (blacklist.isBlacklistedByAnyForm(this.detail)) {
                    return null;
                }
                return Collections.singletonList(ab);
            }
            return null;
        }
        Properties nsOptions = new Properties();
        List<Properties> propsList = new ArrayList<Properties>(4);
        nsOptions.setProperty("list", getFileName());
        List<Destination> dests = getNamingService().lookupAll(this.detail, nsOptions, propsList);
        if (dests == null) {return null;}

        // Check if host is blacklisted
        BlacklistBean blacklist = new BlacklistBean();
        if (blacklist.isBlacklistedByAnyForm(this.detail)) {
            return null;
        }

        List<AddressBean> rv = new ArrayList<AddressBean>(dests.size());
        for (int i = 0; i < dests.size(); i++) {
            AddressBean ab = new AddressBean(this.detail, dests.get(i));
            ab.setProperties(propsList.get(i));
            rv.add(ab);
        }
        return rv;
    }

    /**
     *  @since 0.9.20
     */
    public void export(Writer out) throws IOException {
        Properties searchProps = new Properties();
        // only blockfile needs this
        searchProps.setProperty("list", getFileName());
        if (filter != null) {
            String startsAt = filter.equals("0-9") ? "[0-9]" : filter;
            searchProps.setProperty("startsWith", startsAt);
        }
        if (search != null && search.length() > 0) {
            searchProps.setProperty("search", search.toLowerCase(Locale.US));
        }

        // Get all results to apply blacklist filtering
        Map<String, Destination> allEntries = getNamingService().getEntries(searchProps);
        if (allEntries != null) {
            BlacklistBean blacklist = new BlacklistBean();
            for (Map.Entry<String, Destination> entry : allEntries.entrySet()) {
                String name = entry.getKey();
                Destination dest = entry.getValue();

                // Skip blacklisted entries
                if (blacklist.isBlacklistedByAnyForm(name)) {
                    continue;
                }

                // Write to output
                out.write(name);
                out.write('=');
                out.write(dest.toBase64());
                out.write('\n');
            }
        }
    }

    /**
     *  @return messages about this action
     *  @since 0.9.40
     */
    public String importFile(RequestWrapper wrequest) throws IOException {
        String message = "";
        InputStream in = wrequest.getInputStream("file");
        OutputStream out = null;
        File tmp = null;
        SingleFileNamingService sfns = null;
        try {
            // non-null but zero bytes if no file entered, don't know why
            if (in == null || in.available() <= 0) {
                fail = true;
                return styleMessage(_t("You must enter a file"), fail);
            }
            // copy to temp file
            tmp = new File(_context.getTempDir(), "susidns-import-" + _context.random().nextLong() + ".txt");
            out = new FileOutputStream(tmp);
            DataHelper.copy(in, out);
            in.close();
            in = null;
            out.close();
            out = null;
            // new SingleFileNamingService
            sfns = new SingleFileNamingService(_context, tmp.getAbsolutePath());
            // getEntries, copy over
            Map<String, Destination> entries = sfns.getEntries();
            int count = entries.size();
            if (count <= 0) {
                fail = true;
                return styleMessage(_t("No entries found in file"), fail);
            }
            else {
                NamingService service = getNamingService();
                int added = 0, dup = 0;
                Properties nsOptions = new Properties();
                nsOptions.setProperty("list", getFileName());
                String now = Long.toString(_context.clock().now());
                nsOptions.setProperty("m", now);
                String filename = wrequest.getFilename("file");
                if (filename != null) {nsOptions.setProperty("s", _t("Imported from file {0}", filename));}
                else {nsOptions.setProperty("s", _t("Imported from file"));}
                for (Map.Entry<String, Destination> e : entries.entrySet()) {
                    String host = e.getKey();
                    Destination dest = e.getValue();
                    boolean ok = service.putIfAbsent(host, dest, nsOptions);
                    if (ok) {added++;}
                    else {dup++;}
                }
                StringBuilder buf = new StringBuilder(128);
                if (added > 0) {
                    buf.append(styleMessage(ngettext("Loaded {0} entry from file",
                                                     "Loaded {0} entries from file",
                                                     added), fail));
                }
                if (dup > 0) {
                    buf.append(styleMessage(ngettext("Skipped {0} duplicate entry from file",
                                                     "Skipped {0} duplicate entries from file",
                                                     dup), fail));
                }
                return buf.toString();
            }
        } catch (IOException ioe) {
            fail = true;
            return styleMessage(_t("Import from file failed") + " - " + ioe, fail);
        } finally {
            if (in != null) {
                try {in.close();}
                catch (IOException ioe) {}
            }
            if (out != null) {
                try {out.close();}
                catch (IOException ioe) {}
            }
            // shutdown SFNS
            if (sfns != null) {sfns.shutdown();}
            if (tmp != null) {tmp.delete();}
        }
    }

    /**
     *  @since 0.9.40
     */
    private static String styleMessage(String message, boolean fail) {
        return "<p class=\"messages" + (fail ? " fail" : "") + "\">" + message + "</p>";
    }

    /**
     *  @since 0.9.34
     */
    public boolean haveImagegen() {
        return _context.portMapper().isRegistered("imagegen");
    }
}
