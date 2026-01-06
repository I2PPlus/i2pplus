/*
 * This file is part of SusiMail project for I2P
 * Created on 01.12.2004
 * $Revision: 1.4 $
 * Copyright (C) 2004-2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
package i2p.susi.webmail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Represents an email attachment with metadata and file data.
 * @author user
 * @since public since 0.9.33, was package private
 */
public class Attachment {
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
     * @return the file name
     */
    public String getFileName() {return fileName;}

    /**
     * @return the transfer encoding, non-null
     */
    public String getTransferEncoding() {return transferEncoding;}

    /**
     * @return the content type
     */
    public String getContentType() {return contentType;}

    /**
     * @return an input stream for reading the attachment data
     * @throws IOException if the file cannot be opened for reading
     */
    public InputStream getData() throws IOException {return new FileInputStream(data);}

    /**
     * @return absolute path to the data file
     * @since 0.9.35
     */
    public String getPath() {return data.getAbsolutePath();}

    /**
     * The unencoded size
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