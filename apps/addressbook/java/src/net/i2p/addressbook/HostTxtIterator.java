// License: MIT. See docs/LICENSES.md
package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import net.i2p.client.naming.HostTxtEntry;

import java.nio.charset.StandardCharsets;
/**
 *  A class to iterate through a hosts.txt or config file without
 *  reading the whole thing into memory.
 *  Keys are always converted to lower case.
 *
 *  Callers should iterate all the way through or call close()
 *  to ensure the underlying stream is closed.
 *
 *  This is not used for config files.
 *  It is only used for subscriptions.
 *
 *  @since 0.8.7, renamed from ConfigIterator in 0.9.26
 */
class HostTxtIterator implements Iterator<Map.Entry<String, HostTxtEntry>>, Closeable {

    private BufferedReader input;
    private MapEntry next;

    /**
     *  A dummy iterator in which hasNext() is always false.
     */
    public HostTxtIterator() {
        // Default constructor
    }

    /**
     *  An iterator over the key/value pairs in the file.
     */
    public HostTxtIterator(File file) throws IOException {
            FileInputStream fileStream = new FileInputStream(file);
            input = new BufferedReader(new InputStreamReader(fileStream, StandardCharsets.UTF_8));
    }

    /**
     * Return true if there is another entry to return.
     *
     * @return true if there is another entry
     */
    public boolean hasNext() {
        if (input == null) {return false;}
        if (next != null) {return true;}
        try {
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                HostTxtEntry he = HostTxtParser.parse(inputLine, true);
                if (he == null) {continue;}
                next = new MapEntry(he.getName(), he);
                return true;
            }
        } catch (IOException ioe) {
            // Log the error but continue with cleanup
            System.err.println("Error reading from host file: " + ioe.getMessage());
        }
        try {input.close();}
        catch (IOException ioe) {
            // Log warning but don't throw - we're cleaning up
            System.err.println("Warning: Failed to close host file: " + ioe.getMessage());
        }
        input = null;
        next = null;
        return false;
    }

    /**
     * Return the next entry. 'remove' entries will be returned with a null key,
     * and the value will contain a null name, null dest, and non-null props.
     *
     * @return the next entry
     * @throws NoSuchElementException if there is no next entry
     */
    public Map.Entry<String, HostTxtEntry> next() {
        if (!hasNext()) {throw new NoSuchElementException();}
        Map.Entry<String, HostTxtEntry> rv = next;
        next = null;
        return rv;
    }

    /**
     * Not supported.
     *
     * @throws UnsupportedOperationException always
     */
    public void remove() {throw new UnsupportedOperationException();}

    /**
     * Close the underlying stream.
     */
    public void close() {
        if (input != null) {
            try {input.close();}
            catch (IOException ioe) { /* ignored */ }
        }
    }

    /**
     *  The object returned by the iterator.
     */
    private static class MapEntry implements Map.Entry<String, HostTxtEntry> {
        private final String key;
        private final HostTxtEntry value;

        public MapEntry(String k, HostTxtEntry v) {
            key = k;
            value = v;
        }

        public String getKey() {return key;}

        public HostTxtEntry getValue() {return value;}

        public HostTxtEntry setValue(HostTxtEntry v) {
            throw new UnsupportedOperationException();
        }

        public int hashCode() {return key.hashCode() ^ value.hashCode();}

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {return false;}
            @SuppressWarnings("unchecked")
            Map.Entry<Object, Object> e = (Map.Entry<Object, Object>) o;
            return key.equals(e.getKey()) && value.equals(e.getValue());
        }
    }

}
