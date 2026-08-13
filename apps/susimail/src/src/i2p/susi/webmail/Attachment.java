// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an email attachment with metadata and file data.
 * @since public since 0.9.33, was package private
 */
public class Attachment {
    /**
     * Unique per-process id, used as the attachment id in the compose UI.
     * @since 0.9.71+
     */
    private static final AtomicInteger _nextId = new AtomicInteger(1);
    /**
     * The id of this attachment, unique per process.
     * @since 0.9.71+
     */
    public final int id = _nextId.getAndIncrement();
    /**
     * The file name of the attachment.
     */
    private final String fileName;
    /**
     * The content type of the attachment.
     */
    private final String contentType;
    /**
     * The transfer encoding of the attachment.
     */
    private final String transferEncoding;
    /**
     * The file containing the attachment data.
     */
    private final File data;

    /**
     * Creates a new Attachment.
     *
     * @param name the file name
     * @param type the content type
     * @param encoding the transfer encoding, non-null
     * @param data the file containing the attachment data
     */
    Attachment(String name, String type, String encoding, File data) {
        fileName = name;
        contentType = type;
        transferEncoding = encoding;
        this.data = data;
    }

    /**
     * Returns the file name.
     *
     * @return the file name
     */
    public String getFileName() {return fileName;}

    /**
     * Returns the transfer encoding.
     *
     * @return the transfer encoding, non-null
     */
    public String getTransferEncoding() {return transferEncoding;}

    /**
     * Returns the content type.
     *
     * @return the content type
     */
    public String getContentType() {return contentType;}

    /**
     * Returns an input stream for reading the attachment data.
     *
     * @return an input stream for reading the attachment data
     * @throws IOException if the file cannot be opened for reading
     */
    public InputStream getData() throws IOException {return new FileInputStream(data);}

    /**
     * Returns the absolute path to the data file.
     *
     * @return absolute path to the data file
     * @since 0.9.35
     */
    public String getPath() {return data.getAbsolutePath();}

    /**
     * The unencoded size
     *
     * @return the unencoded size
     * @since 0.9.33
     */
    public long getSize() {return data.length();}

    /**
     * Delete the data file
     * @since 0.9.33
     */
    public void deleteData() {data.delete();}

    /**
     * Returns a hash code based on the file name and data file.
     * @return the hash code
     * @since 0.9.38
     */
    @Override
    public int hashCode() {return fileName.hashCode() ^ data.hashCode();}

    /**
     * Compares this attachment to another for equality.
     * @param o the object to compare with
     * @return true if the attachments are equal
     * @since 0.9.38
     */
    @Override
    public boolean equals (Object o) {
        if (o == null || !(o instanceof Attachment)) {return false;}
        Attachment a = (Attachment) o;
        return fileName.equals(a.fileName) && data.equals(a.data);
    }

}
