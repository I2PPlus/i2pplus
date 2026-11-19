// License: MIT. See docs/LICENSES.md
package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

import java.nio.charset.StandardCharsets;
// TODO: switch to the DataHelper loadProps/storeProps methods?
/**
 * Utility class providing methods to parse and write files in config file
 * format, and subscription file format.
*/
class ConfigParser {

    /** Utility class, not intended for instantiation. */
    private ConfigParser() {
        // Utility class - prevent instantiation
    }

    /** True on Windows, where the path separators differ. */
    private static final boolean isWindows = SystemVersion.isWindows();

    /**
     * Strip the comments from a String. Lines that begin with '#' and ';' are
     * considered comments, as well as any part of a line after a '#'.
     *
     * @param inputLine
     *            A String to strip comments from.
     * @return A String without comments, but otherwise identical to inputLine.
     */
    public static String stripComments(String inputLine) {
        if (inputLine.startsWith(";")) {return "";}
        int hash = inputLine.indexOf('#');
        if (hash >= 0) {return inputLine.substring(0, hash);}
        else {return inputLine;}
    }

    /**
     * Split one line into its key and its value, at the first unescaped '='.
     * Both halves come back trimmed, and the key has its escapes undone, so a
     * key that contains a '=' survives the round trip. That is not hypothetical:
     * the subscription metadata files are keyed by URL, and a cgi URL carries
     * one - http://example.com/hosts.txt?rev=3 - and was cut in half, the first
     * half becoming the key.
     *
     * @param inputLine
     *            A line to split, with its comments already stripped.
     * @return A two element array of the key and the value, or null if the line
     *         holds no unescaped '=' at all.
     * @since 0.9.72+
     */
    private static String[] splitKeyValue(String inputLine) {
        int eq = -1;
        boolean escaped = false;
        for (int i = 0; i < inputLine.length(); i++) {
            char c = inputLine.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '=') {
                eq = i;
                break;
            }
        }
        if (eq < 0) {return null;}
        return new String[] {unescapeKey(inputLine.substring(0, eq).trim()),
                             inputLine.substring(eq + 1).trim()};
    }

    /**
     * Write a key out so splitKeyValue can read it back: a '=' or a backslash in
     * the key is escaped with a backslash. Values are written as they are, since
     * the split stops at the first unescaped '=' and nothing here writes a value
     * that needs escaping.
     *
     * @param key
     *            A key to escape.
     * @return The key, with any '=' or backslash in it escaped.
     * @since 0.9.72+
     */
    private static String escapeKey(String key) {
        if (key.indexOf('=') < 0 && key.indexOf('\\') < 0) {return key;}
        StringBuilder rv = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '=' || c == '\\') {rv.append('\\');}
            rv.append(c);
        }
        return rv.toString();
    }

    /**
     * Undo escapeKey's escapes in a key read back from a file. A backslash with
     * nothing after it is left alone.
     *
     * @param key
     *            A key as read from a file.
     * @return The key as it was before it was written out.
     * @since 0.9.72+
     */
    private static String unescapeKey(String key) {
        if (key.indexOf('\\') < 0) {return key;}
        StringBuilder rv = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\\' && i + 1 < key.length()) {
                i++;
                c = key.charAt(i);
            }
            rv.append(c);
        }
        return rv.toString();
    }

    /**
     * Return a Map using the contents of BufferedReader input. input must have
     * a single key, value pair on each line, in the format: key=value. Lines
     * starting with '#' or ';' are considered comments, and ignored. Lines that
     * are obviously not in the format key=value are also ignored.
     *
     * @param input
     *            A BufferedReader with lines in key=value format to parse into
     *            a Map.
     * @param lowerCaseKeys
     *            Whether to fold each key to lower case. True for a settings file,
     *            whose keys are fixed names the reader compares against; false for
     *            a file whose keys are URLs, which are looked up exactly as written.
     * @return A Map containing the key, value pairs from input.
     * @throws IOException
     *             if the BufferedReader cannot be read.
     *
     */
    private static Map<String, String> parse(BufferedReader input, boolean lowerCaseKeys) throws IOException {
        try {
            Map<String, String> result = new HashMap<>();
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                inputLine = stripComments(inputLine);
                if (inputLine.isEmpty()) {continue;}
                String[] splitLine = splitKeyValue(inputLine);
                if (splitLine != null) {
                    String key = splitLine[0];
                    if (lowerCaseKeys) {key = key.toLowerCase(Locale.US);}
                    result.put(key, splitLine[1]);
                }
            }
            return result;
        } finally {
            try {input.close();}
            catch (IOException ioe) {
                // Ignore IOException
            }
        }
    }

    /**
     * Return a Map using the contents of the File file. See parseBufferedReader
     * for details of the input format. Keys are folded to lower case, as this is
     * the reader for the settings file.
     *
     * @param file
     *            A File to parse.
     * @return A Map containing the key, value pairs from file.
     * @throws IOException
     *             if file cannot be read.
     */
    public static Map<String, String> parse(File file) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return parse(input, true);
        }
    }

    /**
     * Return a Map using the contents of the File file, in the key=value format
     * described for parseBufferedReader, but with the keys left exactly as they
     * were written. This is the reader for the subscription metadata files
     * (etags, last-modified, last-fetched), whose keys are subscription URLs.
     * Those URLs are looked up with the text of the subscriptions file, which is
     * never folded, so folding them here would mean a URL containing any upper
     * case letter never finds its own stored value: the conditional-GET headers
     * are dropped and the last-fetched time resets to zero.
     *
     * @param file
     *            A File to parse.
     * @return A Map containing the key, value pairs from file, keys unfolded.
     * @throws IOException
     *             if file cannot be read.
     * @since 0.9.72+
     */
    public static Map<String, String> parseSubscriptionMetadata(File file) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return parse(input, false);
        }
    }

    /**
     * Return a Map using the contents of the File file. If file cannot be read,
     * use map instead, and write the result to where file should have been.
     *
     * @param file
     *            A File to attempt to parse.
     *
     * @param map
     *            A Map containing values to use as defaults.
     *
     * @return A Map containing the key, value pairs from file, or if file cannot be read, map.
     */
    public static Map<String, String> parse(File file, Map<String, String> map) {
        Map<String, String> result;
        boolean fileParsedSuccessfully = false;

        try {
            result = parse(file);
            fileParsedSuccessfully = true;
        } catch (IOException exp) {result = new HashMap<>(map);} // Avoid modifying original map

        try {
            // Migrate "local_addressbook" to "master_addressbook"
            String localBook = result.remove("local_addressbook");
            if (localBook != null) {result.put("master_addressbook", localBook);}

            // Merge map entries only if they don't exist in result
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            // Only write back if we successfully parsed the file originally
            if (!fileParsedSuccessfully) {write(result, file);}
        } catch (IOException exp) {
                // Ignore IOException
            }
        return result;
    }


    /**
     * Return a List where each element is a line from the BufferedReader input.
     *
     * @param input
     *            A BufferedReader to parse.
     * @return A List consisting of one element for each line in input.
     * @throws IOException
     *             if input cannot be read.
     */
    private static List<String> parseSubscriptions(BufferedReader input) throws IOException {
        try {
            List<String> result = new ArrayList<>(4);
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                inputLine = stripComments(inputLine).trim();
                if (!inputLine.isEmpty()) {result.add(inputLine);}
            }
            return result;
        } finally {
            try {input.close();}
            catch (IOException ioe) {
                // Ignore IOException
            }
        }
    }

    /**
     * Return a List where each element is a line from the File file.
     *
     * @param file
     *            A File to parse.
     * @return A List consisting of one element for each line in file.
     * @throws IOException
     *             if file cannot be read.
     */
    private static List<String> parseSubscriptions(File file) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return parseSubscriptions(input);
        }
    }

    /**
     * Return a List using the contents of the File file. If file cannot be
     * read, use list instead, and write the result to where file should have
     * been.
     *
     * @param file
     *            A File to attempt to parse.
     * @param list The default subscriptions to be saved and returned if the file cannot be read
     * @return A List consisting of one element for each line in file, or if
     *         file cannot be read, list.
     */
    public static List<String> parseSubscriptions(File file, List<String> list) {
        List<String> result;
        try {
            result = parseSubscriptions(file);
            // Fix up files that contain the old default
            // which was changed in 0.9.11
            if (result.remove(Daemon.OLD_DEFAULT_SUB)) {
                for (String sub : list) {
                    if (!result.contains(sub)) {result.add(sub);}
                }
                try {writeSubscriptions(result, file);} // TODO log
                catch (IOException ioe) {
                // Ignore IOException
            }
            }
        } catch (IOException exp) {
            result = list;
            try {writeSubscriptions(result, file);}
            catch (IOException exp2) {
                // Ignore IOException
            }
        }
        return result;
    }

    /**
     * Write contents of Map map to BufferedWriter output. Output is written
     * with one key, value pair on each line, in the format: key=value. A '=' or
     * a backslash in the key is escaped, so a key that contains one - a cgi
     * subscription URL - is read back whole.
     *
     * @param map
     *            A Map to write to output.
     * @param output
     *            A BufferedWriter to write the Map to.
     * @throws IOException
     *             if the BufferedWriter cannot be written to.
     */
    private static void write(Map<String, String> map, BufferedWriter output) throws IOException {
        try {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                output.write(escapeKey(entry.getKey()) + '=' + entry.getValue());
                output.newLine();
            }
        } finally {
            try {output.close();}
            catch (IOException ioe) {
                // Ignore IOException
            }
        }
    }

    /**
     * Write contents of Map map to the File file. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * Write to a temp file in the same directory and then rename, to not corrupt
     * simultaneous accesses by the router. Except on Windows where renameTo()
     * will fail if the target exists.
     *
     * @param map
     *            A Map to write to file.
     * @param file
     *            A File to write the Map to.
     * @throws IOException
     *             if file cannot be written to.
     */
    public static void write(Map<String, String> map, File file) throws IOException {
        boolean success = false;
        if (!isWindows) {
            File tmp = SecureFile.createTempFile("temp-", ".tmp", file.getAbsoluteFile().getParentFile());
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), StandardCharsets.UTF_8)));
            success = tmp.renameTo(file);
            if (!success) {tmp.delete();}
        }
        if (!success) {
            // the rename failed, fall back to writing in place
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), StandardCharsets.UTF_8)));
        }
    }

    /**
     * Write contents of List list to BufferedWriter output. Output is written
     * with each element of list on a new line.
     *
     * @param list
     *            A List to write to file.
     * @param output
     *            A BufferedWriter to write list to.
     * @throws IOException
     *             if output cannot be written to.
     */
    private static void writeSubscriptions(List<String> list, BufferedWriter output) throws IOException {
        try {
            for (String s : list) {
                output.write(s);
                output.newLine();
            }
        } finally {
            try {output.close();}
            catch (IOException ioe) {
                // Ignore IOException
            }
        }
    }

    /**
     * Write contents of List list to File file. Output is written with each
     * element of list on a new line.
     *
     * @param list
     *            A List to write to file.
     * @param file
     *            A File to write list to.
     * @throws IOException
     *             if output cannot be written to.
     */
    private static void writeSubscriptions(List<String> list, File file) throws IOException {
        writeSubscriptions(list, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), StandardCharsets.UTF_8)));
    }

}
