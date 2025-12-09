/******************************************************************
 * CyberUtil for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * Utility class for file operations and XML file detection. This class provides static methods for
 * loading file contents into byte arrays and checking if files have XML extensions.
 */
public final class FileUtil {
    /**
     * Loads the entire contents of a file into a byte array.
     *
     * @param fileName the path to the file to load
     * @return the file contents as a byte array, or empty array if an error occurs
     */
    public static final byte[] load(String fileName) {
        try {
            FileInputStream fin = new FileInputStream(fileName);
            return load(fin);
        } catch (Exception e) {
            Debug.warning(e);
            return new byte[0];
        }
    }

    /**
     * Loads the entire contents of a File object into a byte array.
     *
     * @param file the File object to load
     * @return the file contents as a byte array, or empty array if an error occurs
     */
    public static final byte[] load(File file) {
        try {
            FileInputStream fin = new FileInputStream(file);
            return load(fin);
        } catch (Exception e) {
            Debug.warning(e);
            return new byte[0];
        }
    }

    /**
     * Loads the entire contents of a FileInputStream into a byte array.
     *
     * @param fin the FileInputStream to read from
     * @return the stream contents as a byte array, or empty array if an error occurs
     */
    public static final byte[] load(FileInputStream fin) {
        byte readBuf[] = new byte[512 * 1024];

        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();

            int readCnt = fin.read(readBuf);
            while (0 < readCnt) {
                bout.write(readBuf, 0, readCnt);
                readCnt = fin.read(readBuf);
            }

            fin.close();

            return bout.toByteArray();
        } catch (Exception e) {
            Debug.warning(e);
            return new byte[0];
        }
    }

    /**
     * Checks if a filename has an XML extension.
     *
     * @param name the filename to check
     * @return true if the filename ends with ".xml" (case-insensitive), false otherwise
     */
    public static final boolean isXMLFileName(String name) {
        if (StringUtil.hasData(name) == false) return false;
        String lowerName = name.toLowerCase(Locale.US);
        return lowerName.endsWith("xml");
    }
}
