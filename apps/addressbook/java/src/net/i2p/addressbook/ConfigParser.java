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

import net.i2p.data.DataHelper;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 * Utility class providing methods to parse and write files in config file
 * format, and subscription file format.
 *
 * TODO: switch to the DataHelper loadProps/storeProps methods?
 *
 * @author Ragnarok
 */
class ConfigParser {

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
     * Return a Map using the contents of BufferedReader input. input must have
     * a single key, value pair on each line, in the format: key=value. Lines
     * starting with '#' or ';' are considered comments, and ignored. Lines that
     * are obviously not in the format key=value are also ignored.
     * The key is converted to lower case.
     *
     * @param input
     *            A BufferedReader with lines in key=value format to parse into
     *            a Map.
     * @return A Map containing the key, value pairs from input.
     * @throws IOException
     *             if the BufferedReader cannot be read.
     *
     */
    private static Map<String, String> parse(BufferedReader input) throws IOException {
        try {
            Map<String, String> result = new HashMap<String, String>();
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                inputLine = stripComments(inputLine);
                if (inputLine.length() == 0) {continue;}
                String[] splitLine = DataHelper.split(inputLine, "=", 2);
                if (splitLine.length == 2) {
                    result.put(splitLine[0].trim().toLowerCase(Locale.US), splitLine[1].trim());
                }
            }
            return result;
        } finally {
            try {input.close();}
            catch (IOException ioe) {}
        }
    }

    /**
     * Return a Map using the contents of the File file. See parseBufferedReader
     * for details of the input format.
     *
     * @param file
     *            A File to parse.
     * @return A Map containing the key, value pairs from file.
     * @throws IOException
     *             if file cannot be read.
     */
    public static Map<String, String> parse(File file) throws IOException {
        FileInputStream fileStream = null;
        try {
            fileStream = new FileInputStream(file);
            BufferedReader input = new BufferedReader(new InputStreamReader(fileStream, "UTF-8"));
            Map<String, String> rv = parse(input);
            return rv;
        } finally {
            if (fileStream != null) {
                try {fileStream.close();}
                catch (IOException ioe) {}
            }
        }
    }

    /**
     * Return a Map using the contents of the File file. If file cannot be read,
     * use map instead, and write the result to where file should have been.
     *
     * @param file
     * A File to attempt to parse.
     *
     * @param map
     * A Map containing values to use as defaults.
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
        } catch (IOException exp) {}
        return result;
    }

/*    public static Map<String, String> parse(File file, Map<String, String> map) {
        Map<String, String> result;
        try {
            result = parse(file);
            // migrate from I2P
            String master = result.remove("local_addressbook");
            if (master != null) {result.put("master_addressbook", master);}
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (IOException exp) {
            result = map;
            try {write(result, file);}
            catch (IOException exp2) {}
        }
        return result;
    }
*/

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
            List<String> result = new ArrayList<String>(4);
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                inputLine = stripComments(inputLine).trim();
                if (inputLine.length() > 0) {result.add(inputLine);}
            }
            return result;
        } finally {
            try {input.close();}
            catch (IOException ioe) {}
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
        FileInputStream fileStream = null;
        try {
            fileStream = new FileInputStream(file);
            BufferedReader input = new BufferedReader(new InputStreamReader(fileStream, "UTF-8"));
            List<String> rv = parseSubscriptions(input);
            return rv;
        } finally {
            if (fileStream != null) {
                try {fileStream.close();}
                catch (IOException ioe) {}
            }
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
                catch (IOException ioe) {}
            }
        } catch (IOException exp) {
            result = list;
            try {writeSubscriptions(result, file);}
            catch (IOException exp2) {}
        }
        return result;
    }

    /**
     * Write contents of Map map to BufferedWriter output. Output is written
     * with one key, value pair on each line, in the format: key=value.
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
                output.write(entry.getKey() + '=' + entry.getValue());
                output.newLine();
            }
        } finally {
            try {output.close();}
            catch (IOException ioe) {}
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
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), "UTF-8")));
            success = tmp.renameTo(file);
            if (!success) {tmp.delete();}
        }
        if (!success) {
            // hmm, that didn't work, try it the old way
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
        }
    }

    /**
     * Write contents of List list to BufferedReader output. Output is written
     * with each element of list on a new line.
     *
     * @param list
     *            A List to write to file.
     * @param output
     *            A BufferedReader to write list to.
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
            catch (IOException ioe) {}
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
        writeSubscriptions(list, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
    }

}
