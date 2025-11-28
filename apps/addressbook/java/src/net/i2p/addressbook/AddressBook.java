/*
 * Copyright (c) 2004 Ragnarok
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.util.EepGet;
import net.i2p.util.SecureFile;

/**
 * An address book for storing human readable names mapped to base64 i2p
 * destinations. AddressBooks can be created from local and remote files, merged
 * together, and written out to local files.
 *
 * Methods are NOT thread-safe.
 *
 * @author Ragnarok
 *
 */
class AddressBook implements Iterable<Map.Entry<String, HostTxtEntry>> {

    private final String location;
    /** either addresses or subFile will be non-null, but not both */
    private final Map<String, HostTxtEntry> addresses;
    private final File subFile;
    private boolean modified;
    private static final boolean DEBUG = false;

    private static final int MIN_DEST_LENGTH = 516;
    private static final int MAX_DEST_LENGTH = MIN_DEST_LENGTH + 100;  // longer than any known cert type for now

    /**
     * 5-67 chars lower/upper case
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[0-9a-zA-Z\\.-]{5,67}$");

    /**
     * 52 chars lower/upper case
     * Always ends in 'a' or 'q'
     */
    private static final Pattern B32_PATTERN = Pattern.compile("^[2-7a-zA-Z]{51}[aAqQ]$");

    /** not a complete qualification, just a quick check */
    private static final Pattern B64_PATTERN = Pattern.compile("^[0-9a-zA-Z~-]{" + MIN_DEST_LENGTH + ',' + MAX_DEST_LENGTH + "}={0,2}$");

    /**
     * Construct an AddressBook from the contents of the Map addresses.
     *
     * @param addresses A Map containing human readable addresses as keys, mapped to base64 i2p destinations.
     */
    public AddressBook(Map<String, HostTxtEntry> addresses) {
        this.addresses = addresses;
        this.subFile = null;
        this.location = null;
    }

    /** Maximum permitted size of a hosts subscription file - 5MB or around 8000 hosts */
    static final long MAX_SUB_SIZE = 5 * 1024 * 1024l;

    /**
     * Construct an AddressBook from the Subscription subscription. If the
     * address book at subscription has not changed since the last time it was
     * read or cannot be read, return an empty AddressBook.
     * Set a maximum size of the remote book to make it a little harder for a malicious book-sender.
     *
     * Yes, the EepGet fetch() is done in this constructor.
     *
     * This stores the subscription in a temporary file and does not read the whole thing into memory.
     * An AddressBook created with this constructor may not be modified or written using write().
     * It may be a merge source (an parameter for another AddressBook's merge())
     * but may not be a merge target (this.merge() will throw an exception).
     *
     * @param subscription A Subscription instance pointing at a remote address book.
     * @param proxyHost hostname of proxy
     * @param proxyPort port number of proxy
     */
    public AddressBook(Subscription subscription, String proxyHost, int proxyPort) {
        Map<String, HostTxtEntry> a = Collections.emptyMap(); // initialize to avoid null
        File subf = null;
        File tmp = null;
        try {
            tmp = SecureFile.createTempFile("addressbook", null, I2PAppContext.getGlobalContext().getTempDir());
            // Apache 2.4 mod_deflate etag bug workaround (Gitlab #454)
            String loc = subscription.getLocation();
            String etag = subscription.getEtag();
            if (loc.startsWith("http://i2p-projekt.i2p/") && etag != null && etag.endsWith("-gzip\"")) {
                etag = etag.substring(0, etag.length() - 6) + '"'; // Strip -gzip from the etag
            }
            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true, proxyHost, proxyPort, 10, -1L, MAX_SUB_SIZE,
                                    tmp.getAbsolutePath(), null, loc, true, etag, subscription.getLastModified(), null);
            if (get.fetch()) {
                subscription.setEtag(get.getEtag());
                subscription.setLastModified(get.getLastModified());
                subscription.setLastFetched(I2PAppContext.getGlobalContext().clock().now());
                subf = tmp;
                String lastMod = (get.getLastModified() != null ? get.getLastModified() : "n/a");
                String eTag = get.getEtag();
                boolean hasLastMod = get.getLastModified() != null;
                boolean hasEtag = get.getEtag() != null;
                System.out.println("Checking [" + loc.replace("http://", "") + "] -> " + (
                                   hasLastMod ? "Last modified: " + lastMod :
                                   hasEtag ? "ETag: " + eTag :
                                   "No ETag or Last Modified headers"));
                a = Collections.emptyMap(); // Addresses not loaded here, so keep empty map
            } else {
                a = Collections.emptyMap();
                tmp.delete();
            }
        } catch (IOException ioe) {
            if (tmp != null) {
                tmp.delete();
            }
            a = Collections.emptyMap();
        }
        this.addresses = a;
        this.subFile = subf;
        this.location = subscription.getLocation();
    }

    /**
     * Construct an AddressBook from the contents of the file at file.
     * If the file cannot be read, construct an empty AddressBook.
     * This reads the entire file into memory.
     * The resulting map is modifiable and may be a merge target.
     *
     * @param file
     * A File pointing at a file with lines in the format "key=value",
     * where key is a human readable name, and value is a base64 i2p destination.
     */
    public AddressBook(File file) {
        this.location = file.toString();
        Map<String, HostTxtEntry> a;
        try {a = HostTxtParser.parse(file);}
        catch (IOException exp) {a = new HashMap<String, HostTxtEntry>();}
        this.addresses = a;
        this.subFile = null;
    }

    /**
     * Test only.
     *
     * @param testsubfile path to a file containing the simulated fetch of a subscription
     * @since 0.9.26
     */
    public AddressBook(String testsubfile) {
        this.location = testsubfile;
        this.addresses = null;
        this.subFile = new File(testsubfile);
    }

    /**
     * Return an iterator over the addresses in the AddressBook.
     * @since 0.8.7
     */
    public Iterator<Map.Entry<String, HostTxtEntry>> iterator() {
        if (this.subFile != null) {
            try {return new HostTxtIterator(this.subFile);}
            catch (IOException ioe) {return new HostTxtIterator();}
        }
        return this.addresses.entrySet().iterator();
    }

    /**
     * Delete the temp file or clear the map.
     * @since 0.8.7
     */
    public void delete() {
        if (this.subFile != null) {
            this.subFile.delete();
        }
        else if (this.addresses != null) {
            try {this.addresses.clear();}
            catch (UnsupportedOperationException uoe) {
                // Clearing not supported, ignore
            }
        }
    }

    /**
     * Return the location of the file this AddressBook was constructed from.
     *
     * @return A String representing either an abstract path, or a url,
     *         depending on how the instance was constructed.
     *         Will be null if created with the Map constructor.
     */
    public String getLocation() {return this.location;}

    /**
     * Return a string representation of the origin of the AddressBook.
     *
     * @return A String representing the origin of the AddressBook.
     */
    @Override
    public String toString() {
        if (this.location != null) {
            return "Book from " + this.location;
        }
        return "Map containing " + this.addresses.size() + " entries";
    }

    /**
     * Basic validation of the hostname.
     * Already converted to lower case by HostTxtParser.parse()
     * @param host the hostname to validate
     * @return true if the hostname is valid, false otherwise
     */
    public static boolean isValidKey(String host) {
        final int len = host.length();

        // Basic suffix & length checks
        if (!host.endsWith(".i2p")) {
            return false;
        }
        if (len <= 4 || len > 67) {
            return false; // max 63 chars + ".i2p"
        }

        if (host.startsWith(".") || host.startsWith("-")) {
            return false;
        }
        if (host.contains("..") || host.contains(".-") || host.contains("-.")) {
            return false;
        }

        // IDN check: '--' allowed only in punycode prefix/suffix
        if (host.contains("--") && !host.startsWith("xn--") && host.indexOf(".xn--") < 0) {
            return false;
        }

        // Check reserved exact names and reserved suffixes
        String[] reservedExact = { "proxy.i2p", "router.i2p", "console.i2p", "b32.i2p" };
        for (String res : reservedExact) {
            if (host.equals(res)) {
                return false;
            }
        }

        String[] reservedSuffixes = { ".proxy.i2p", ".router.i2p", ".console.i2p", ".b32.i2p" };
        for (String suffix : reservedSuffixes) {
            if (host.endsWith(suffix)) {
                return false;
            }
        }

        if (!HOST_PATTERN.matcher(host).matches()) {
            return false;
        }

        // Base32 special check: only if length == 56
        if (len == 56 && B32_PATTERN.matcher(host.substring(0, 52)).matches()) {
            return false;
        }

        return true;
    }

    /**
     * Do basic validation of the b64 dest, without bothering to instantiate it
     * @param dest the destination to validate
     * @return true if the destination is valid, false otherwise
     */
    private static boolean isValidDest(String dest) {
        if (dest == null) {
            return false;  // Defensive null check
        }

        final int len = dest.length();

        // null cert special case: must be exactly MIN_DEST_LENGTH and end with "AA"
        if (len == MIN_DEST_LENGTH) {
            if (!dest.endsWith("AA")) {
                return false;
            }
        } else {
            // Must be larger than MIN_DEST_LENGTH and no greater than MAX_DEST_LENGTH
            if (len <= MIN_DEST_LENGTH || len > MAX_DEST_LENGTH) {
                return false;
            }
        }

        // Base64 strings never have length mod 4 == 1
        int mod = len % 4;
        if (mod == 1) {
            return false;
        }

        // Regex check for valid base64 characters (fast fail if invalid)
        return B64_PATTERN.matcher(dest).matches();
    }

    /**
     * Merge this AddressBook with AddressBook other, writing messages about new addresses
     * or conflicts to log. Addresses in AddressBook other that are not in this AddressBook
     * are added to this AddressBook.
     *
     * In case of a conflict, addresses in this AddressBook take precedence
     *
     * @param other An AddressBook to merge with.
     * @param overwrite True to overwrite
     * @param log The log to write messages about new addresses or conflicts to. May be null.
     *
     * @throws IllegalStateException if this was created with the Subscription constructor.
     */
    public void merge(AddressBook other, boolean overwrite, Log log) {
        if (this.addresses == null) {
            throw new IllegalStateException();
        }
        Iterator<Map.Entry<String, HostTxtEntry>> iter = other.iterator();
        try {merge2(other, iter, overwrite, log);}
        finally {
            if (iter instanceof HostTxtIterator) {
                ((HostTxtIterator) iter).close();
            }
        }
    }

    private void merge2(AddressBook other, Iterator<Map.Entry<String, HostTxtEntry>> iter, boolean overwrite, Log log) {
        while(iter.hasNext()) {
            Map.Entry<String, HostTxtEntry> entry = iter.next();
            String otherKey = entry.getKey();
            HostTxtEntry otherValue = entry.getValue();

            if (isValidKey(otherKey) && isValidDest(otherValue.getDest())) {
                if (this.addresses.containsKey(otherKey) && !overwrite) {
                    if (DEBUG && log != null && !this.addresses.get(otherKey).equals(otherValue.getDest())) {
                        log.append("Conflict for " + otherKey + " from " + other.location +
                                   ". Destination in remote address book is " + otherValue);
                    }
                } else if (!this.addresses.containsKey(otherKey) || !this.addresses.get(otherKey).equals(otherValue)) {
                    this.addresses.put(otherKey, otherValue);
                    this.modified = true;
                    if (log != null) {
                        log.append("New domain " + otherKey + " [" + other.location + "]");
                    }
                }
            }
        }
    }

    /**
     * Write the contents of this AddressBook out to the File file. If the file
     * cannot be writen to, this method will silently fail.
     *
     * @param file The file to write the contents of this AddressBook too.
     *
     * @throws IllegalStateException if this was created with the Subscription constructor.
     */
    public void write(File file) {
        if (this.addresses == null) {
            throw new IllegalStateException();
        }
        if (this.modified) {
            try {HostTxtParser.write(this.addresses, file);}
            catch (IOException exp) {
                System.err.println("Error writing addressbook " + file.getAbsolutePath() + " : " + exp.toString());
            }
        }
    }

    /**
     * Write this AddressBook out to the file it was read from. Requires that
     * AddressBook was constructed from a file on the local filesystem. If the
     * file cannot be writen to, this method will silently fail.
     *
     * @throws IllegalStateException if this was not created with the File constructor.
     */
    public void write() {
        if (this.location == null || this.location.startsWith("http://")) {
            throw new IllegalStateException();
        }
        this.write(new File(this.location));
    }

}
