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
    private final String fileName, contentType, transferEncoding;
    private final File data;

    /**
     * @param type the content type
     * @param encoding the transfer encoding, non-null
     */
    Attachment(String name, String type, String encoding, File data) {
        fileName = name;
        contentType = type;
        transferEncoding = encoding;
        this.data = data;
    }

    /**
     * @return Returns the fileName.
     */
    public String getFileName() {return fileName;}

    /**
     * @return non-null
     */
    public String getTransferEncoding() {return transferEncoding;}

    public String getContentType() {return contentType;}

    /**
     * @return Returns the data.
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
     * @since 0.9.38
     */
    @Override
    public int hashCode() {return fileName.hashCode() ^ data.hashCode();}

    /**
     * @since 0.9.38
     */
    @Override
    public boolean equals (Object o) {
        if (o == null || !(o instanceof Attachment)) {return false;}
        Attachment a = (Attachment) o;
        return fileName.equals(a.fileName) && data.equals(a.data);
    }

}