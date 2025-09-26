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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SystemVersion;

/**
 * Main class of addressbook.  Performs updates, and runs the main loop.
 * As of 0.9.30, package private, run with DaemonThread.
 *
 * @author Ragnarok
 *
 */
class Daemon {
    public static final String VERSION = "2.0.4";
    private volatile boolean _running;
    private static final boolean DEBUG = false;
    // If you change this, change in SusiDNS SubscriptionBean also
    private static final String DEFAULT_SUB = "http://stats.i2p/cgi-bin/newhosts.txt" + "\n" +
                                              "http://skank.i2p/hosts.txt" + "\n" +
                                              "http://notbob.i2p/hosts.txt";
    /** @since 0.9.12 */
    static final String OLD_DEFAULT_SUB = "http://www.i2p2.i2p/hosts.txt";
    /** Any properties we receive from the subscription, we store to the
     *  addressbook with this prefix, so it knows it's part of the signature.
     *  This is also chosen so that it can't be spoofed.
     */
    private static final String RCVD_PROP_PREFIX = "=";
    private static final boolean MUST_VALIDATE = false;

    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     *
     * @param master
     * The master AddressBook. This address book is never overwritten, so it is safe
     * for the user to write to. It is only merged to the published addressbook. May be null.
     *
     * @param router
     * The router AddressBook. This is the address book read by client applications.
     *
     * @param published
     * The published AddressBook. This address book is published on the user's eepsite
     * so that others may subscribe to it. May be null. If non-null, overwrite with the
     * new addressbook.
     *
     * @param subscriptions
     * A SubscriptionList listing the remote address books to update from.
     *
     * @param log
     * The log to write changes and conflicts to. May be null.
     */
    public static void update(AddressBook master, AddressBook router,
        File published, SubscriptionList subscriptions, Log log) {
        for (AddressBook book : subscriptions) {router.merge(book, false, log);} // yes, the EepGet fetch() is done in next()
        router.write();
        if (published != null) {
            if (master != null) {router.merge(master, true, null);}
            router.write(published);
        }
        subscriptions.write();
    }

    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     * Merging of the "master" addressbook is NOT supported.
     *
     * @param router
     * The NamingService to update, generally the root NamingService from the context.
     *
     * @param published
     * The published AddressBook. This address book is published on the user's eepsite
     * so that others may subscribe to it. May be null. If non-null, overwrite with the
     * new addressbook.
     *
     * @param subscriptions
     * A SubscriptionList listing the remote address books to update from.
     *
     * @param log
     * The log to write changes and conflicts to. May be null.
     * @since 0.8.7
     */
    public static void update(NamingService router, File published, SubscriptionList subscriptions, Log log) {
        /*
         * If the NamingService is a database, we look up as we go.
         * If it is a text file, we do things differently, to avoid O(n**2) behavior
         * when scanning large subscription results (i.e. those that return the whole file, not just the new entries) -
         * we load all the known hostnames into a Set one time.
         * This also has the advantage of not flushing the NamingService's LRU cache.
         */
        String nsClass = router.getClass().getSimpleName();
        boolean isTextFile = nsClass.equals("HostsTxtNamingService") || nsClass.equals("SingleFileNamingService");
        Set<String> knownNames;
        if (isTextFile) {
            // load the hostname set
            Properties opts = new Properties();
            opts.setProperty("file", "hosts.txt");
            knownNames = router.getNames(opts);
        } else {knownNames = null;}
        NamingService publishedNS;
        if (published != null) {
            publishedNS = new SingleFileNamingService(I2PAppContext.getGlobalContext(), published.getAbsolutePath());
        } else {publishedNS = null;}

        Iterator<AddressBook> iter = subscriptions.iterator();
        while (iter.hasNext()) { // yes, the EepGet fetch() is done in next()
            long start = System.currentTimeMillis();
            AddressBook addressbook = iter.next();
            // SubscriptionIterator puts in a dummy AddressBook with no location if no fetch is done
            if (DEBUG && log != null && addressbook.getLocation() != null) {
                long end = System.currentTimeMillis();
                log.append("Fetch of " + addressbook.getLocation() + " took " + (end - start));
            }
            Iterator<Map.Entry<String, HostTxtEntry>> iter2 = addressbook.iterator();
            try {
                update(router, knownNames, publishedNS, addressbook, iter2, log);
            } finally {
                if (iter2 instanceof HostTxtIterator) {((HostTxtIterator) iter2).close();}
                addressbook.delete();
            }
        }  // subscriptions
        subscriptions.write();
    }

    /**
     * Updates the router and published naming services from entries in an address book.
     * Processes each entry with appropriate validations, actions, and logs changes.
     *
     * @param router       NamingService to update with new and changed entries.
     * @param knownNames   Set of known hostnames, non-null if the router book is text based.
     * @param publishedNS  Published NamingService to update after router changes.
     * @param addressbook  AddressBook providing location context for entries.
     * @param iter         Iterator over entries mapping hostnames to HostTxtEntry objects.
     * @param log          Log for appending informational and error messages.
     */
    private static void update(NamingService router, Set<String> knownNames,
                               NamingService publishedNS, AddressBook addressbook,
                               Iterator<Map.Entry<String, HostTxtEntry>> iter, Log log) {
        long start = DEBUG ? System.currentTimeMillis() : 0;
        int old = 0, nnew = 0, invalid = 0, deleted = 0;
        while (iter.hasNext()) {
            Map.Entry<String, HostTxtEntry> entry = iter.next();
            String key = entry.getKey();
            HostTxtEntry he = entry.getValue();
            boolean isKnown = knownNames != null
                    ? key != null && knownNames.contains(key)
                    : key != null && router.lookup(key) != null;

            try {
                if (!validateEntry(key, he, knownNames, router, addressbook, log)) {
                    invalid++;
                    continue;
                }

                String action = (he.getProps() != null) ? he.getProps().getProperty(HostTxtEntry.PROP_ACTION) : null;
                if (action == null && !isKnown) {
                    if (log != null) log.append("No action and unknown key " + key + " [" + addressbook.getLocation() + "]");
                    invalid++;
                    continue;
                }

                if (key != null) {
                    if (!AddressBook.isValidKey(key)) {
                        if (log != null) log.append("Bad hostname " + key + " [" + addressbook.getLocation() + "]");
                        invalid++;
                        continue;
                    }
                    if (processActionForKey(action, he, key, isKnown, router, publishedNS, knownNames, addressbook, log)) {
                        nnew++;
                    } else {
                        old++;
                    }
                } else {
                    if (!processRemoveAction(action, he, router, publishedNS, knownNames, addressbook, log)) {
                        invalid++;
                    } else {
                        deleted++;
                    }
                }
            } catch (DataFormatException dfe) {
                if (log != null) log.append("Invalid b64 for " + key + " [" + addressbook.getLocation() + "]");
                invalid++;
            }
        }
        if (DEBUG && log != null) {
            log.append("Merge of " + addressbook.getLocation() + " into " + router +
                    " took " + (System.currentTimeMillis() - start) + " ms with " +
                    "new: " + nnew + ", old: " + old + ", deleted: " + deleted + ", invalid: " + invalid);
        }
    }

    /**
     * Validates the signature and properties of a HostTxtEntry.
     *
     * @param key          The hostname key associated with the entry.
     * @param he           The HostTxtEntry to validate.
     * @param knownNames   The set of known hostnames, if any.
     * @param router       The NamingService router to query if needed.
     * @param addressbook  The AddressBook context for logging.
     * @param log          Optional log to record validation failures.
     * @return true if the entry passes validation; false otherwise.
     */
    private static boolean validateEntry(String key, HostTxtEntry he, Set<String> knownNames,
                                         NamingService router, AddressBook addressbook, Log log) {
        Properties hprops = he.getProps();
        boolean mustValidate = MUST_VALIDATE || hprops != null;
        String action = (hprops != null) ? hprops.getProperty(HostTxtEntry.PROP_ACTION) : null;

        if (key == null && !he.hasValidRemoveSig()) {
            if (log != null) log.append("Bad signature for action " + action + " with null key [" + addressbook.getLocation() + "]");
            return false;
        }
        if (key != null && mustValidate && !he.hasValidSig()) {
            if (log != null) log.append("Bad signature of action " + action + " for key " + key + " [" + addressbook.getLocation() + "]");
            return false;
        }
        return true;
    }

    /**
     * Processes an action for a known key in the router.
     *
     * @param action       The action command string.
     * @param he           The HostTxtEntry containing the destination and properties.
     * @param key          The hostname key.
     * @param isKnown      True if the key is known to the router.
     * @param router       The NamingService router to update.
     * @param publishedNS  The published NamingService to update as well.
     * @param knownNames   Set of known hostnames to update.
     * @param addressbook  The AddressBook context for logging.
     * @param log          Optional log to record processing messages.
     * @return true if a new or updated entry was added; false if it was an old/unchanged entry.
     * @throws DataFormatException if destination data is invalid.
     */
    private static boolean processActionForKey(String action, HostTxtEntry he, String key, boolean isKnown,
                                               NamingService router, NamingService publishedNS, Set<String> knownNames,
                                               AddressBook addressbook, Log log) throws DataFormatException {
        Properties hprops = he.getProps();
        Destination dest = new Destination(he.getDest());
        Properties props = new OrderedProperties();
        props.setProperty("s", addressbook.getLocation());
        if (MUST_VALIDATE || hprops != null)
            props.setProperty("v", "true");

        if (hprops != null) {
            for (Map.Entry<Object, Object> e : hprops.entrySet()) {
                props.setProperty(RCVD_PROP_PREFIX + e.getKey(), (String) e.getValue());
            }
        }

        boolean allowExistingKeyInPublished = false;

        switch (action) {
            case HostTxtEntry.ACTION_ADDDEST:
                return handleAddDest(he, key, dest, router, publishedNS, props, addressbook, log);
            case HostTxtEntry.ACTION_ADDNAME:
                return handleAddName(he, key, dest, router, isKnown, log, addressbook);
            case HostTxtEntry.ACTION_ADDSUBDOMAIN:
                return handleAddSubdomain(he, key, dest, router, isKnown, log, addressbook);
            case HostTxtEntry.ACTION_CHANGEDEST:
                allowExistingKeyInPublished = handleChangeDest(he, key, dest, router, log, addressbook);
                break;
            case HostTxtEntry.ACTION_CHANGENAME:
                return handleChangeName(he, key, dest, router, publishedNS, knownNames, log, addressbook);
            case HostTxtEntry.ACTION_REMOVE:
            case HostTxtEntry.ACTION_REMOVEALL:
                if (log != null) log.append("Action: " + action + " with name=dest invalid [" + addressbook.getLocation() + "]");
                return false;
            case HostTxtEntry.ACTION_UPDATE:
                if (isKnown) {
                    allowExistingKeyInPublished = true;
                    props.setProperty("m", Long.toString(I2PAppContext.getGlobalContext().clock().now()));
                }
                break;
            default:
                if (log != null) log.append("Action: " + action + " unrecognized [" + addressbook.getLocation() + "]");
                return false;
        }

        boolean success = router.put(key, dest, props);
        if (log != null) {
            if (success)
                log.append("New domain " + key + " [" + addressbook.getLocation() + "]");
            else
                log.append("Save to naming service " + router + " failed for new key " + key);
        }

        if (publishedNS != null) {
            if (allowExistingKeyInPublished)
                success = publishedNS.put(key, dest, props);
            else
                success = publishedNS.putIfAbsent(key, dest, props);
            if (log != null && !success) {
                log.append("Failed to save " + key + " to published addressbook [" + publishedNS.getName() + "]");
            }
        }

        if (knownNames != null) knownNames.add(key);

        return success;
    }

    /**
     * Processes remove-related actions for entries with null keys.
     *
     * @param action       The remove action command string.
     * @param he           The HostTxtEntry containing the properties.
     * @param router       The NamingService router to update.
     * @param publishedNS  The published NamingService to update as well.
     * @param knownNames   Set of known hostnames to update.
     * @param addressbook  The AddressBook context for logging.
     * @param log          Optional log to record processing messages.
     * @return true if removal succeeded; false otherwise.
     */
    private static boolean processRemoveAction(String action, HostTxtEntry he,
                                               NamingService router, NamingService publishedNS,
                                               Set<String> knownNames, AddressBook addressbook, Log log) {
        if (action == null) {
            if (log != null) log.append("No action in command line [" + addressbook.getLocation() + "]");
            return false;
        }

        if (action.equals(HostTxtEntry.ACTION_REMOVE) || action.equals(HostTxtEntry.ACTION_REMOVEALL)) {
            Destination polddest = null;
            try {
                String polddestStr = he.getProps() != null ? he.getProps().getProperty(HostTxtEntry.PROP_DEST) : null;
                if (polddestStr != null)
                    polddest = new Destination(polddestStr);
            } catch (DataFormatException dfe) {
                if (log != null) log.append("Invalid destination format [" + addressbook.getLocation() + "]");
                return false;
            }

            if (polddest == null) {
                logMissing(log, action, "delete", addressbook);
                return false;
            }

            String poldname = he.getProps().getProperty(HostTxtEntry.PROP_NAME);

            if (HostTxtEntry.ACTION_REMOVE.equals(action)) {
                if (poldname != null) {
                    return removeDestination(router, publishedNS, knownNames, poldname, polddest, log, addressbook);
                }
                logMissing(log, action, "delete", addressbook);
                return false;
            } else { // REMOVEALL
                boolean allSuccess = true;
                if (poldname != null) {
                    allSuccess &= removeDestination(router, publishedNS, knownNames, poldname, polddest, log, addressbook);
                }
                if (router != null) {
                    List<String> revs = router.reverseLookupAll(polddest);
                    if (revs != null) {
                        for (String rev : revs) {
                            allSuccess &= removeDestination(router, publishedNS, knownNames, rev, polddest, log, addressbook);
                        }
                    }
                }
                return allSuccess;
            }
        }

        if (log != null)
            log.append("Action: " + action + " without name=dest unrecognized [" + addressbook.getLocation() + "]");
        return false;
    }

    /**
     * Attempts to remove a destination from the router and published services.
     *
     * @param router       The NamingService router.
     * @param publishedNS  The published NamingService.
     * @param knownNames   The known hostname set, if any.
     * @param name         The hostname to remove.
     * @param dest         The destination to remove.
     * @param log          Optional log for messages.
     * @param addressbook  Context AddressBook.
     * @return true if removal succeeded; false otherwise.
     */
    private static boolean removeDestination(NamingService router, NamingService publishedNS,
                                             Set<String> knownNames, String name, Destination dest,
                                             Log log, AddressBook addressbook) {
        List<Destination> pod2 = router.lookupAll(name);
        if (pod2 != null && pod2.contains(dest)) {
            if (knownNames != null && pod2.size() == 1) knownNames.remove(name);
            boolean success = router.remove(name, dest);
            if (log != null) {
                if (success)
                    log.append("Removed: " + name + " as requested [" + addressbook.getLocation() + "]");
                else
                    log.append("Remove failed for: " + name + " as requested [" + addressbook.getLocation() + "]");
            }
            if (publishedNS != null) {
                success = publishedNS.remove(name, dest);
                if (log != null && !success)
                    log.append("Failed to remove " + name + " from published addressbook [" + publishedNS.getName() + "]");
            }
            return success;
        }
        if (pod2 != null) {
            logMismatch(log, HostTxtEntry.ACTION_REMOVE, name, pod2, dest.toBase64(), addressbook);
            return false;
        }
        return false;
    }

    /**
     * Handles the ADDDEST action: add an alternate destination for an existing hostname.
     *
     * @return true if a new destination was successfully added, false if it was already known or invalid.
     */
    private static boolean handleAddDest(HostTxtEntry he, String key, Destination dest,
                                         NamingService router, NamingService publishedNS,
                                         Properties props, AddressBook addressbook, Log log) throws DataFormatException {
        String polddest = he.getProps().getProperty(HostTxtEntry.PROP_OLDDEST);
        if (polddest == null) {
            logMissing(log, HostTxtEntry.ACTION_ADDDEST, key, addressbook);
            return false;
        }
        Destination pod;
        try {
            pod = new Destination(polddest);
        } catch (DataFormatException e) {
            if (log != null) log.append("Invalid destination format [" + addressbook.getLocation() + "]");
            return false;
        }

        List<Destination> pod2 = router.lookupAll(key);
        if (pod2 == null) {
            // New entry — check inner signature
            if (!he.hasValidInnerSig()) {
                logInner(log, HostTxtEntry.ACTION_ADDDEST, key, addressbook);
                return false;
            }
        } else if (pod2.contains(dest)) {
            // Known destination
            return false;
        } else if (pod2.contains(pod)) {
            // Valid alternate dest, check inner signature
            if (!he.hasValidInnerSig()) {
                logInner(log, HostTxtEntry.ACTION_ADDDEST, key, addressbook);
                return false;
            }
            boolean success = router.addDestination(key, dest, props);
            if (log != null) {
                log.append(success ? "Additional address for " + key + " [" + addressbook.getLocation() + "]"
                                  : "Failed to add additional address for " + key + " [" + addressbook.getLocation() + "]");
            }
            if (publishedNS != null) {
                success = publishedNS.addDestination(key, dest, props);
                if (log != null && !success)
                    log.append("Failed to add " + key + " to published addressbook [" + publishedNS.getName() + "]");
            }
            return success;
        } else {
            logMismatch(log, HostTxtEntry.ACTION_ADDDEST, key, pod2, he.getDest(), addressbook);
            return false;
        }
        return false;
    }

    /**
     * Handles the ADDNAME action: add an alias for an existing hostname with the same destination.
     *
     * @return true if added new, false if duplicate or invalid.
     */
    private static boolean handleAddName(HostTxtEntry he, String key, Destination dest,
                                         NamingService router, boolean isKnown, Log log, AddressBook addressbook) {
        if (isKnown) return false;
        String poldname = he.getProps().getProperty(HostTxtEntry.PROP_OLDNAME);
        if (poldname == null) {
            logMissing(log, HostTxtEntry.ACTION_ADDNAME, key, addressbook);
            return false;
        }
        List<Destination> pod = router.lookupAll(poldname);
        if (pod == null || pod.contains(dest)) {
            return true; // Valid to add
        }
        logMismatch(log, HostTxtEntry.ACTION_ADDNAME, key, pod, he.getDest(), addressbook);
        return false;
    }

    /**
     * Handles the ADDSUBDOMAIN action: add a subdomain with verification.
     *
     * @return true if added new, false if duplicate or invalid.
     */
    private static boolean handleAddSubdomain(HostTxtEntry he, String key, Destination dest,
                                              NamingService router, boolean isKnown, Log log, AddressBook addressbook) {
        if (isKnown) return false;
        Properties hprops = he.getProps();
        String polddest = hprops.getProperty(HostTxtEntry.PROP_OLDDEST);
        String poldname = hprops.getProperty(HostTxtEntry.PROP_OLDNAME);
        if (polddest == null || poldname == null) {
            logMissing(log, HostTxtEntry.ACTION_ADDSUBDOMAIN, key, addressbook);
            return false;
        }
        if (!AddressBook.isValidKey(poldname) || key.indexOf('.' + poldname) <= 0) {
            if (log != null)
                log.append("Action: " + HostTxtEntry.ACTION_ADDSUBDOMAIN + " failed because old name " + poldname +
                           " is invalid [" + addressbook.getLocation() + "]");
            return false;
        }
        Destination pod;
        try {
            pod = new Destination(polddest);
        } catch (DataFormatException e) {
            if (log != null) log.append("Invalid destination format [" + addressbook.getLocation() + "]");
            return false;
        }
        List<Destination> pod2 = router.lookupAll(poldname);
        if (pod2 == null) {
            if (!he.hasValidInnerSig()) {
                logInner(log, HostTxtEntry.ACTION_ADDSUBDOMAIN, key, addressbook);
                return false;
            }
        } else if (!pod2.contains(pod)) {
            logMismatch(log, HostTxtEntry.ACTION_ADDSUBDOMAIN, key, pod2, polddest, addressbook);
            return false;
        } else if (!he.hasValidInnerSig()) {
            logInner(log, HostTxtEntry.ACTION_ADDSUBDOMAIN, key, addressbook);
            return false;
        }
        return true;
    }

    /**
     * Handles the CHANGEDEST action: change destination on an existing entry, replacing old destination.
     *
     * @return true if existing key in published naming service should be allowed.
     */
    private static boolean handleChangeDest(HostTxtEntry he, String key, Destination dest,
                                            NamingService router, Log log, AddressBook addressbook) {
        Properties hprops = he.getProps();
        String polddest = hprops.getProperty(HostTxtEntry.PROP_OLDDEST);
        if (polddest == null) {
            logMissing(log, HostTxtEntry.ACTION_CHANGEDEST, key, addressbook);
            return false;
        }
        Destination pod;
        try {
            pod = new Destination(polddest);
        } catch (DataFormatException e) {
            if (log != null) log.append("Invalid destination format [" + addressbook.getLocation() + "]");
            return false;
        }
        List<Destination> pod2 = router.lookupAll(key);
        if (pod2 == null) {
            if (!he.hasValidInnerSig()) {
                logInner(log, HostTxtEntry.ACTION_CHANGEDEST, key, addressbook);
                return false;
            }
            return false;
        } else if (pod2.contains(dest)) {
            return false;  // Already have new dest
        } else if (!pod2.contains(pod)) {
            logMismatch(log, HostTxtEntry.ACTION_CHANGEDEST, key, pod2, polddest, addressbook);
            return false;
        } else {
            if (!he.hasValidInnerSig()) {
                logInner(log, HostTxtEntry.ACTION_CHANGEDEST, key, addressbook);
                return false;
            }
            if (log != null) {
                if (pod2.size() == 1)
                    log.append("Changing destination for " + key + ". [" + addressbook.getLocation() + "]");
                else
                    log.append("Replacing " + pod2.size() + " destinations for " + key + " [" + addressbook.getLocation() + "]");
            }
            return true;
        }
    }

    /**
     * Handles the CHANGENAME action: delete old name and replace with new name.
     *
     * @return true if replacement succeeded, false otherwise.
     */
    private static boolean handleChangeName(HostTxtEntry he, String key, Destination dest,
                                            NamingService router, NamingService publishedNS,
                                            Set<String> knownNames, Log log, AddressBook addressbook) {
        if (knownNames != null && knownNames.contains(key)) {
            return false; // Already known
        }
        String poldname = he.getProps().getProperty(HostTxtEntry.PROP_OLDNAME);
        if (poldname == null) {
            logMissing(log, HostTxtEntry.ACTION_CHANGENAME, key, addressbook);
            return false;
        }
        List<Destination> pod = router.lookupAll(poldname);
        if (pod == null) {
            return true; // Old name unknown, so replacement is valid
        } else if (!pod.contains(dest)) {
            logMismatch(log, HostTxtEntry.ACTION_CHANGENAME, key, pod, he.getDest(), addressbook);
            return false;
        } else {
            if (knownNames != null)
                knownNames.remove(poldname);
            boolean success = router.remove(poldname, dest);
            if (log != null) {
                if (success)
                    log.append("Removed: " + poldname + " to be replaced with " + key + " [" + addressbook.getLocation() + "]");
                else
                    log.append("Remove failed for: " + poldname + " to be replaced with " + key + " [" + addressbook.getLocation() + "]");
            }
            if (success && publishedNS != null) {
                success = publishedNS.remove(poldname, dest);
                if (log != null && !success)
                    log.append("Remove from published addressbook " + publishedNS.getName() + " failed for " + poldname);
            }
            return success;
        }
    }

    /** @since 0.9.26 */
    private static void logInner(Log log, String action, String name, AddressBook addressbook) {
        if (log != null) {
            log.append("Action: " + action + " failed because" +
                       " inner signature for key " + name +
                       " failed" +
                       " [" + addressbook.getLocation() + "]");
        }
    }

    /** @since 0.9.26 */
    private static void logMissing(Log log, String action, String name, AddressBook addressbook) {
        if (log != null) {
            log.append("Action: " + action + " for " + name +
                       " failed, missing required parameters" +
                       " [" + addressbook.getLocation() + "]");
        }
    }

    /** @since 0.9.26 */
    private static void logMismatch(Log log, String action, String name, List<Destination> dests,
                                    String olddest, AddressBook addressbook) {
        if (log != null) {
            StringBuilder buf = new StringBuilder(16);
            final int sz = dests.size();
            for (int i = 0; i < sz; i++) {
                buf.append(dests.get(i).toBase64().substring(0, 6));
                if (i != sz - 1)
                    buf.append(", ");
            }
            log.append("Action: " + action + " failed because" +
                       " destinations for " + name +
                       " (" + buf + ')' +
                       " do not include" +
                       " (" + olddest.substring(0, 6) + ')' +
                       " [" + addressbook.getLocation() + "]");
        }
    }

    /**
     * Run an update, using the Map settings to provide the parameters.
     *
     * @param settings
     * A Map containg the parameters needed by update.
     * @param home
     * The directory containing addressbook's configuration files.
     */
    public static void update(Map<String, String> settings, String home) {
        File published = null;
        boolean should_publish = Boolean.parseBoolean(settings.get("should_publish"));
        if (should_publish)
            published = new File(home, settings.get("published_addressbook"));
        File subscriptionFile = new File(home, settings.get("subscriptions"));
        File logFile = new File(home, settings.get("log"));
        File etagsFile = new File(home, settings.get("etags"));
        File lastModifiedFile = new File(home, settings.get("last_modified"));
        File lastFetchedFile = new File(home, settings.get("last_fetched"));
        long delay;
        try {
            delay = Long.parseLong(settings.get("update_delay"));
        } catch (NumberFormatException nfe) {
            delay = 12;
        }
        delay *= 60 * 60 * 1000;

        List<String> defaultSubs = new ArrayList<String>(4);
        defaultSubs.add(DEFAULT_SUB);
        SubscriptionList subscriptions = new SubscriptionList(subscriptionFile,
                                                              etagsFile, lastModifiedFile, lastFetchedFile,
                                                              delay, defaultSubs, settings.get("proxy_host"),
                                                              Integer.parseInt(settings.get("proxy_port")));
        Log log = SystemVersion.isAndroid() ? null : new Log(logFile);

        // If false, add hosts via naming service; if true, write hosts.txt file directly
        // Default false
        if (Boolean.parseBoolean(settings.get("update_direct"))) {
            // Direct hosts.txt access
            File routerFile = new File(home, settings.get("router_addressbook"));
            AddressBook master;
            if (should_publish) {
                File masterFile = new File(home, settings.get("master_addressbook"));
                master = new AddressBook(masterFile);
            } else {
                master = null;
            }
            AddressBook router = new AddressBook(routerFile);
            update(master, router, published, subscriptions, log);
        } else {
            // Naming service - no merging of master to router and published is supported.
            update(getNamingService(settings.get("naming_service")), published, subscriptions, log);
        }
    }

    /** depth-first search */
    private static NamingService searchNamingService(NamingService ns, String srch) {
        String name = ns.getName();
        if (name.equals(srch) || name.endsWith('/' + srch) || name.endsWith('\\' + srch)) {
            return ns;
        }
        List<NamingService> list = ns.getNamingServices();
        if (list != null) {
            for (NamingService nss : list) {
                NamingService rv = searchNamingService(nss, srch);
                if (rv != null) {return rv;}
            }
        }
        return null;
    }

    /** @return the configured NamingService, or the root NamingService */
    private static NamingService getNamingService(String srch) {
        NamingService root = I2PAppContext.getGlobalContext().namingService();
        NamingService rv = searchNamingService(root, srch);
        return rv != null ? rv : root;
    }

    /**
     * Load the settings, set the proxy, then enter into the main loop. The main
     * loop performs an immediate update, and then an update every number of
     * hours, as configured in the settings file.
     *
     * @param args
     * Command line arguments. If there are any arguments provided,
     * the first is taken as addressbook's home directory, and the
     * others are ignored.
     */
    public static void main(String[] args) {
        Daemon daemon = new Daemon();
        if (args.length > 0 && args[0].equals("test")) {daemon.test(args);}
        else {daemon.run(args);}
    }

    /** @since 0.9.26 */
    public static void test(String[] args) {
        Properties ctxProps = new Properties();
        String PROP_FORCE = "i2p.naming.blockfile.writeInAppContext";
        ctxProps.setProperty(PROP_FORCE, "true");
        I2PAppContext ctx = new I2PAppContext(ctxProps);
        NamingService ns = getNamingService("hosts.txt");
        File published = new File("test-published.txt");
        Log log = new Log(new File("test-log.txt"));
        SubscriptionList subscriptions = new SubscriptionList("test-sub.txt");
        update(ns, published, subscriptions, log);
        ctx.logManager().flush();
    }

    /**
     * Runs the main application loop which loads settings, ensures directories,
     * then periodically performs updates based on configuration until stopped.
     *
     * @param args optional argument where args[0] specifies the home directory;
     *             if absent, defaults to current working directory. May be null.
     */
    public void run(String[] args) {
        _running = true;
        final String settingsLocation = "config.txt";

        final File homeFile = determineHomeDirectory(args);
        ensureDirectoryExists(homeFile);

        final Map<String, String> defaultSettings = getDefaultSettings();
        final File settingsFile = new File(homeFile, settingsLocation);

        Map<String, String> settings = ConfigParser.parse(settingsFile, defaultSettings);

        waitRandomInitialDelay();

        while (_running) {
            long delayHours = parseUpdateDelay(settings);
            update(settings, homeFile.getAbsolutePath());
            waitDelay(delayHours);
            if (!_running) break;

            settings = ConfigParser.parse(settingsFile, defaultSettings);
        }
    }

    /** Returns the home directory File from args or current working directory if absent. */
    private File determineHomeDirectory(String[] args) {
        if (args != null && args.length > 0) {
            File home = new SecureDirectory(args[0]);
            if (!home.isAbsolute()) {
                home = new SecureDirectory(I2PAppContext.getGlobalContext().getRouterDir(), args[0]);
            }
            return home;
        }
        return new SecureDirectory(System.getProperty("user.dir"));
    }

    /** Creates directory if it doesn't exist; logs error if creation fails. */
    private void ensureDirectoryExists(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            System.out.println("ERROR: Addressbook directory " + directory.getAbsolutePath() + " could not be created");
        }
    }

    /** Returns a map of default configuration key-value pairs. */
    private Map<String, String> getDefaultSettings() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("proxy_host", "127.0.0.1");
        defaults.put("proxy_port", "4444");
        defaults.put("master_addressbook", "../userhosts.txt");
        defaults.put("router_addressbook", "../hosts.txt");
        defaults.put("published_addressbook", "../eepsite/docroot/hosts.txt");
        defaults.put("should_publish", "false");
        defaults.put("log", "log.txt");
        defaults.put("subscriptions", "subscriptions.txt");
        defaults.put("etags", "etags");
        defaults.put("last_modified", "last_modified");
        defaults.put("last_fetched", "last_fetched");
        defaults.put("update_delay", "3");
        defaults.put("update_direct", "false");
        defaults.put("naming_service", "hosts.txt");
        return defaults;
    }

    /** Sleeps for 3-6 minutes to stagger startup; ignores interrupts. */
    private void waitRandomInitialDelay() {
        try {
            long base = 3 * 60 * 1000L;
            long rand = I2PAppContext.getGlobalContext().random().nextLong(base);
            Thread.sleep(base + rand);
        } catch (InterruptedException ignored) {}
    }

    /** Parses update delay from settings, enforcing minimum of 1 hour. */
    private long parseUpdateDelay(Map<String, String> settings) {
        try {
            return Math.max(Long.parseLong(settings.get("update_delay")), 1);
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Waits for the specified delay in hours, allowing interruption to stop early. */
    private void waitDelay(long delayHours) {
        try {
            synchronized (this) {
                wait(delayHours * 60 * 60 * 1000L);
            }
        } catch (InterruptedException ignored) {}
    }


    /**
     * Call this to get the addressbook to reread its config and
     * refetch its subscriptions.
     */
    public void wakeup() {
        synchronized (this) {notifyAll();}
    }

    public void stop() {
        _running = false;
        wakeup();
    }

}