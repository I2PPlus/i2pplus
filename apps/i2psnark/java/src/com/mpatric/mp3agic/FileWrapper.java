package com.mpatric.mp3agic;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class FileWrapper {

    protected Path path;
    protected long length;
    protected long lastModified;

    protected FileWrapper() {}

    /**
     * Constructs a FileWrapper from the specified filename.
     *
     * @param filename the path to the file
     * @throws IOException if the file does not exist or is not readable
     */
    public FileWrapper(String filename) throws IOException {
        this.path = Paths.get(filename);
        init();
    }

    /**
     * Constructs a FileWrapper from the specified File object.
     *
     * @param file the File object to wrap
     * @throws IOException if the file does not exist or is not readable
     * @throws NullPointerException if file is null
     */
    public FileWrapper(File file) throws IOException {
        if (file == null) throw new NullPointerException();
        this.path = Paths.get(file.getPath());
        init();
    }

    /**
     * Constructs a FileWrapper from the specified Path object.
     *
     * @param path the Path object to wrap
     * @throws IOException if the file does not exist or is not readable
     * @throws NullPointerException if path is null
     */
    public FileWrapper(Path path) throws IOException {
        if (path == null) throw new NullPointerException();
        this.path = path;
        init();
    }

    private void init() throws IOException {
        if (!Files.exists(path)) throw new FileNotFoundException("File not found " + path);
        if (!Files.isReadable(path)) throw new IOException("File not readable");
        length = Files.size(path);
        lastModified = Files.getLastModifiedTime(path).to(TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the filename as a string.
     *
     * @return the filename
     */
    public String getFilename() {
        return path.toString();
    }

    /**
     * Returns the length of the file in bytes.
     *
     * @return the file length in bytes
     */
    public long getLength() {
        return length;
    }

    /**
     * Returns the last modified time of the file in milliseconds since epoch.
     *
     * @return the last modified time
     */
    public long getLastModified() {
        return lastModified;
    }
}
