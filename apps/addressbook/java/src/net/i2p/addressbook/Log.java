package net.i2p.addressbook;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import java.nio.charset.StandardCharsets;
/**
 * A simple log with automatic time stamping.
 *
 * @author Ragnarok
 *
 */
class Log {

    private static final int MAX_LINES = 600;
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*Z--.*");

    private final File file;

    /**
     * Construct a Log instance that writes to the File file.
     *
     * @param file
     *            A File for the log to write to.
     */
    public Log(File file) {
        this.file = file;
    }

    /**
     * Write entry to a new line in the log, with appropriate time stamp.
     *
     * @param entry
     *            A String containing a message to append to the log.
     */
    public void append(String entry) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(this.file, true)), StandardCharsets.UTF_8))) {
            String timestamp = Instant.now().toString();
            bw.write(timestamp);
            bw.write(" -- ");
            bw.write(entry);
            bw.newLine();
            trimLog();
        } catch (IOException exp) { /* ignored */ }
    }

    private void trimLog() {
        try {
            Path path = file.toPath();
            List<String> lines = Files.readAllLines(path);
            if (lines.size() > MAX_LINES) {
                int remove = lines.size() - MAX_LINES;
                StringBuilder sb = new StringBuilder();
                for (int i = remove; i < lines.size(); i++) {
                    sb.append(fixEntry(lines.get(i))).append('\n');
                }
                Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) { /* ignored */ }
    }

    private String fixEntry(String line) {
        if (DATE_PATTERN.matcher(line).matches()) {
            return line.replaceFirst("Z--", "Z -- ");
        }
        return line;
    }

}
