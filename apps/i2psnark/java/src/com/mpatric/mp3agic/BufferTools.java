package com.mpatric.mp3agic;

import java.io.UnsupportedEncodingException;

public final class BufferTools {

    protected static final String defaultCharsetName = "ISO-8859-1";

    private BufferTools() {}

    /**
     * Converts a byte buffer to a string using the default charset, ignoring any encoding issues.
     *
     * @param bytes the byte array to convert
     * @param offset the starting offset in the byte array
     * @param length the number of bytes to convert
     * @return the resulting string, or null if encoding fails
     */
    public static String byteBufferToStringIgnoringEncodingIssues(
            byte[] bytes, int offset, int length) {
        try {
            return byteBufferToString(bytes, offset, length, defaultCharsetName);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Converts a byte buffer to a string using the default charset.
     *
     * @param bytes the byte array to convert
     * @param offset the starting offset in the byte array
     * @param length the number of bytes to convert
     * @return the resulting string
     * @throws UnsupportedEncodingException if the default charset is not supported
     */
    public static String byteBufferToString(byte[] bytes, int offset, int length)
            throws UnsupportedEncodingException {
        return byteBufferToString(bytes, offset, length, defaultCharsetName);
    }

    /**
     * Converts a byte buffer to a string using the specified charset.
     *
     * @param bytes the byte array to convert
     * @param offset the starting offset in the byte array
     * @param length the number of bytes to convert
     * @param charsetName the name of the charset to use
     * @return the resulting string
     * @throws UnsupportedEncodingException if the specified charset is not supported
     */
    public static String byteBufferToString(
            byte[] bytes, int offset, int length, String charsetName)
            throws UnsupportedEncodingException {
        if (length < 1) return "";
        return new String(bytes, offset, length, charsetName);
    }

    /**
     * Converts a string to a byte buffer using the default charset, ignoring any encoding issues.
     *
     * @param s the string to convert
     * @param offset the starting offset in the string
     * @param length the number of characters to convert
     * @return the resulting byte array, or null if encoding fails
     */
    public static byte[] stringToByteBufferIgnoringEncodingIssues(
            String s, int offset, int length) {
        try {
            return stringToByteBuffer(s, offset, length);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Converts a string to a byte buffer using the default charset.
     *
     * @param s the string to convert
     * @param offset the starting offset in the string
     * @param length the number of characters to convert
     * @return the resulting byte array
     * @throws UnsupportedEncodingException if the default charset is not supported
     */
    public static byte[] stringToByteBuffer(String s, int offset, int length)
            throws UnsupportedEncodingException {
        return stringToByteBuffer(s, offset, length, defaultCharsetName);
    }

    /**
     * Converts a string to a byte buffer using the specified charset.
     *
     * @param s the string to convert
     * @param offset the starting offset in the string
     * @param length the number of characters to convert
     * @param charsetName the name of the charset to use
     * @return the resulting byte array
     * @throws UnsupportedEncodingException if the specified charset is not supported
     */
    public static byte[] stringToByteBuffer(String s, int offset, int length, String charsetName)
            throws UnsupportedEncodingException {
        String stringToCopy = s.substring(offset, offset + length);
        return stringToCopy.getBytes(charsetName);
    }

    /**
     * Copies a string into a byte buffer using the default charset.
     *
     * @param s the string to copy
     * @param offset the starting offset in the string
     * @param length the number of characters to copy
     * @param bytes the destination byte array
     * @param destOffset the starting offset in the destination byte array
     * @throws UnsupportedEncodingException if the default charset is not supported
     */
    public static void stringIntoByteBuffer(
            String s, int offset, int length, byte[] bytes, int destOffset)
            throws UnsupportedEncodingException {
        stringIntoByteBuffer(s, offset, length, bytes, destOffset, defaultCharsetName);
    }

    /**
     * Copies a string into a byte buffer using the specified charset.
     *
     * @param s the string to copy
     * @param offset the starting offset in the string
     * @param length the number of characters to copy
     * @param bytes the destination byte array
     * @param destOffset the starting offset in the destination byte array
     * @param charsetName the name of the charset to use
     * @throws UnsupportedEncodingException if the specified charset is not supported
     */
    public static void stringIntoByteBuffer(
            String s, int offset, int length, byte[] bytes, int destOffset, String charsetName)
            throws UnsupportedEncodingException {
        String stringToCopy = s.substring(offset, offset + length);
        byte[] srcBytes = stringToCopy.getBytes(charsetName);
        if (srcBytes.length > 0) {
            System.arraycopy(srcBytes, 0, bytes, destOffset, srcBytes.length);
        }
    }

    /**
     * Trims whitespace characters from the right side of a string.
     *
     * @param s the string to trim
     * @return the trimmed string
     */
    public static String trimStringRight(String s) {
        int endPosition = s.length() - 1;
        char endChar;
        while (endPosition >= 0) {
            endChar = s.charAt(endPosition);
            if (endChar > 32) {
                break;
            }
            endPosition--;
        }
        if (endPosition == s.length() - 1) return s;
        else if (endPosition < 0) return "";
        return s.substring(0, endPosition + 1);
    }

    /**
     * Pads a string on the right side with the specified character to reach the desired length.
     *
     * @param s the string to pad
     * @param length the desired length of the resulting string
     * @param padWith the character to use for padding
     * @return the padded string
     */
    public static String padStringRight(String s, int length, char padWith) {
        if (s.length() >= length) return s;
        StringBuilder stringBuffer = new StringBuilder(s);
        while (stringBuffer.length() < length) {
            stringBuffer.append(padWith);
        }
        return stringBuffer.toString();
    }

    /**
     * Checks whether a specific bit is set in a byte.
     *
     * @param b the byte to check
     * @param bitPosition the bit position (0-7, where 0 is the least significant bit)
     * @return true if the bit is set, false otherwise
     */
    public static boolean checkBit(byte b, int bitPosition) {
        return ((b & (0x01 << bitPosition)) != 0);
    }

    /**
     * Sets or clears a specific bit in a byte.
     *
     * @param b the original byte
     * @param bitPosition the bit position (0-7, where 0 is the least significant bit)
     * @param value true to set the bit, false to clear it
     * @return the modified byte
     */
    public static byte setBit(byte b, int bitPosition, boolean value) {
        byte newByte;
        if (value) {
            newByte = (byte) (b | ((byte) 0x01 << bitPosition));
        } else {
            newByte = (byte) (b & (~((byte) 0x01 << bitPosition)));
        }
        return newByte;
    }

    /**
     * Shifts a byte left or right by the specified number of places.
     *
     * @param c the byte to shift
     * @param places the number of places to shift (negative for left shift, positive for right shift)
     * @return the shifted value as an integer
     */
    public static int shiftByte(byte c, int places) {
        int i = c & 0xff;
        if (places < 0) {
            return i << -places;
        } else if (places > 0) {
            return i >> places;
        }
        return i;
    }

    /**
     * Unpacks four bytes into a 32-bit integer (big-endian format).
     *
     * @param b1 the most significant byte
     * @param b2 the second most significant byte
     * @param b3 the second least significant byte
     * @param b4 the least significant byte
     * @return the unpacked integer
     */
    public static int unpackInteger(byte b1, byte b2, byte b3, byte b4) {
        int value = b4 & 0xff;
        value += BufferTools.shiftByte(b3, -8);
        value += BufferTools.shiftByte(b2, -16);
        value += BufferTools.shiftByte(b1, -24);
        return value;
    }

    /**
     * Packs a 32-bit integer into four bytes (big-endian format).
     *
     * @param i the integer to pack
     * @return a byte array containing the packed integer
     */
    public static byte[] packInteger(int i) {
        byte[] bytes = new byte[4];
        bytes[3] = (byte) (i & 0xff);
        bytes[2] = (byte) ((i >> 8) & 0xff);
        bytes[1] = (byte) ((i >> 16) & 0xff);
        bytes[0] = (byte) ((i >> 24) & 0xff);
        return bytes;
    }

    /**
     * Unpacks four bytes from a synchsafe integer into a regular integer.
     * Synchsafe integers are used in ID3v2 tags where the most significant bit of each byte is always 0.
     *
     * @param b1 the most significant byte
     * @param b2 the second most significant byte
     * @param b3 the second least significant byte
     * @param b4 the least significant byte
     * @return the unpacked integer
     */
    public static int unpackSynchsafeInteger(byte b1, byte b2, byte b3, byte b4) {
        int value = ((byte) (b4 & 0x7f));
        value += shiftByte((byte) (b3 & 0x7f), -7);
        value += shiftByte((byte) (b2 & 0x7f), -14);
        value += shiftByte((byte) (b1 & 0x7f), -21);
        return value;
    }

    /**
     * Packs an integer into a synchsafe integer byte array.
     * Synchsafe integers are used in ID3v2 tags where the most significant bit of each byte is always 0.
     *
     * @param i the integer to pack
     * @return a byte array containing the synchsafe integer
     */
    public static byte[] packSynchsafeInteger(int i) {
        byte[] bytes = new byte[4];
        packSynchsafeInteger(i, bytes, 0);
        return bytes;
    }

    /**
     * Packs an integer into a synchsafe integer and stores it in the specified byte array at the given offset.
     * Synchsafe integers are used in ID3v2 tags where the most significant bit of each byte is always 0.
     *
     * @param i the integer to pack
     * @param bytes the destination byte array
     * @param offset the offset in the destination array where the synchsafe integer should be stored
     */
    public static void packSynchsafeInteger(int i, byte[] bytes, int offset) {
        bytes[offset + 3] = (byte) (i & 0x7f);
        bytes[offset + 2] = (byte) ((i >> 7) & 0x7f);
        bytes[offset + 1] = (byte) ((i >> 14) & 0x7f);
        bytes[offset + 0] = (byte) ((i >> 21) & 0x7f);
    }

    /**
     * Creates a copy of a portion of a byte buffer.
     *
     * @param bytes the source byte array
     * @param offset the starting offset in the source array
     * @param length the number of bytes to copy
     * @return a new byte array containing the copied data
     */
    public static byte[] copyBuffer(byte[] bytes, int offset, int length) {
        byte[] copy = new byte[length];
        if (length > 0) {
            System.arraycopy(bytes, offset, copy, 0, length);
        }
        return copy;
    }

    /**
     * Copies a portion of a byte buffer into another byte buffer.
     *
     * @param bytes the source byte array
     * @param offset the starting offset in the source array
     * @param length the number of bytes to copy
     * @param destBuffer the destination byte array
     * @param destOffset the starting offset in the destination array
     */
    public static void copyIntoByteBuffer(
            byte[] bytes, int offset, int length, byte[] destBuffer, int destOffset) {
        if (length > 0) {
            System.arraycopy(bytes, offset, destBuffer, destOffset, length);
        }
    }

    /**
     * Calculates how many bytes would be added to the buffer by applying unsynchronisation.
     * Unsynchronisation is used in ID3v2 tags to avoid false synchronization patterns.
     *
     * @param bytes the byte array to analyze
     * @return the number of bytes that would be added by unsynchronisation
     */
    public static int sizeUnsynchronisationWouldAdd(byte[] bytes) {
        int count = 0;
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == (byte) 0xff
                    && ((bytes[i + 1] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 1] == 0)) {
                count++;
            }
        }
        if (bytes.length > 0 && bytes[bytes.length - 1] == (byte) 0xff) count++;
        return count;
    }

    /**
     * Applies unsynchronisation to a byte buffer.
     * Unsynchronisation replaces instances of:
     * 11111111 111xxxxx with 11111111 00000000 111xxxxx and
     * 11111111 00000000 with 11111111 00000000 00000000
     * This is used in ID3v2 tags to avoid false synchronization patterns.
     *
     * @param bytes the byte array to unsynchronise
     * @return the unsynchronised byte array
     */
    public static byte[] unsynchroniseBuffer(byte[] bytes) {
        // unsynchronisation is replacing instances of:
        // 11111111 111xxxxx with 11111111 00000000 111xxxxx and
        // 11111111 00000000 with 11111111 00000000 00000000
        int count = sizeUnsynchronisationWouldAdd(bytes);
        if (count == 0) return bytes;
        byte[] newBuffer = new byte[bytes.length + count];
        int j = 0;
        for (int i = 0; i < bytes.length - 1; i++) {
            newBuffer[j++] = bytes[i];
            if (bytes[i] == (byte) 0xff
                    && ((bytes[i + 1] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 1] == 0)) {
                newBuffer[j++] = 0;
            }
        }
        newBuffer[j++] = bytes[bytes.length - 1];
        if (bytes[bytes.length - 1] == (byte) 0xff) {
            newBuffer[j++] = 0;
        }
        return newBuffer;
    }

    /**
     * Calculates how many bytes would be removed from the buffer by applying synchronisation.
     * Synchronisation reverses the unsynchronisation process used in ID3v2 tags.
     *
     * @param bytes byte array to analyze
     * @return the number of bytes that would be removed by synchronisation
     */
    public static int sizeSynchronisationWouldSubtract(byte[] bytes) {
        int count = 0;
        for (int i = 0; i < bytes.length - 2; i++) {
            if (bytes[i] == (byte) 0xff
                    && bytes[i + 1] == 0
                    && ((bytes[i + 2] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 2] == 0)) {
                count++;
            }
        }
        if (bytes.length > 1
                && bytes[bytes.length - 2] == (byte) 0xff
                && bytes[bytes.length - 1] == 0) count++;
        return count;
    }

    /**
     * Applies synchronisation to a byte buffer.
     * Synchronisation replaces instances of:
     * 11111111 00000000 111xxxxx with 11111111 111xxxxx and
     * 11111111 00000000 00000000 with 11111111 00000000
     * This reverses the unsynchronisation process used in ID3v2 tags.
     *
     * @param bytes byte array to synchronise
     * @return synchronised byte array
     */
    public static byte[] synchroniseBuffer(byte[] bytes) {
        // synchronisation is replacing instances of:
        // 11111111 00000000 111xxxxx with 11111111 111xxxxx and
        // 11111111 00000000 00000000 with 11111111 00000000
        int count = sizeSynchronisationWouldSubtract(bytes);
        if (count == 0) return bytes;
        byte[] newBuffer = new byte[bytes.length - count];
        int i = 0;
        for (int j = 0; j < newBuffer.length - 1; j++) {
            newBuffer[j] = bytes[i];
            if (bytes[i] == (byte) 0xff
                    && bytes[i + 1] == 0
                    && ((bytes[i + 2] & (byte) 0xe0) == (byte) 0xe0 || bytes[i + 2] == 0)) {
                i++;
            }
            i++;
        }
        newBuffer[newBuffer.length - 1] = bytes[i];
        return newBuffer;
    }

    /**
     * Replaces all occurrences of a substring within a string with another substring.
     *
     * @param s the original string
     * @param replaceThis the substring to replace
     * @param withThis the substring to replace with (null to remove occurrences)
     * @return the resulting string with replacements
     */
    public static String substitute(String s, String replaceThis, String withThis) {
        if (replaceThis.length() < 1 || !s.contains(replaceThis)) {
            return s;
        }
        StringBuilder newString = new StringBuilder();
        int lastPosition = 0;
        int position = 0;
        while ((position = s.indexOf(replaceThis, position)) >= 0) {
            if (position > lastPosition) {
                newString.append(s.substring(lastPosition, position));
            }
            if (withThis != null) {
                newString.append(withThis);
            }
            lastPosition = position + replaceThis.length();
            position++;
        }
        if (lastPosition < s.length()) {
            newString.append(s.substring(lastPosition));
        }
        return newString.toString();
    }

    /**
     * Converts a string to ASCII-only format, replacing non-ASCII characters with question marks.
     *
     * @param s the string to convert
     * @return the ASCII-only string
     */
    public static String asciiOnly(String s) {
        StringBuilder newString = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch > 126) {
                newString.append('?');
            } else {
                newString.append(ch);
            }
        }
        return newString.toString();
    }

    /**
     * Finds the first null terminator in a byte array.
     *
     * @param bytes the byte array to search
     * @return the index of the first null terminator, or -1 if not found
     */
    public static int indexOfTerminator(byte[] bytes) {
        return indexOfTerminator(bytes, 0);
    }

    /**
     * Finds the first null terminator in a byte array starting from the specified index.
     *
     * @param bytes byte array to search
     * @param fromIndex index to start searching from
     * @return index of the first null terminator, or -1 if not found
     */
    public static int indexOfTerminator(byte[] bytes, int fromIndex) {
        return indexOfTerminator(bytes, 0, 1);
    }

    /**
     * Finds the first null terminator of specified length in a byte array starting from the specified index.
     *
     * @param bytes byte array to search
     * @param fromIndex index to start searching from
     * @param terminatorLength length of the null terminator (1 for single null byte, 2 for double null bytes)
     * @return index of the first null terminator, or -1 if not found
     */
    public static int indexOfTerminator(byte[] bytes, int fromIndex, int terminatorLength) {
        int marker = -1;
        for (int i = fromIndex; i <= bytes.length - terminatorLength; i++) {
            if ((i - fromIndex) % terminatorLength == 0) {
                int matched;
                for (matched = 0; matched < terminatorLength; matched++) {
                    if (bytes[i + matched] != 0) break;
                }
                if (matched == terminatorLength) {
                    marker = i;
                    break;
                }
            }
        }
        return marker;
    }

    /**
     * Finds the first null terminator in a byte array based on the text encoding.
     * UTF-16 and UTF-16BE encodings use double null terminators, others use single null terminators.
     *
     * @param bytes byte array to search
     * @param fromIndex index to start searching from
     * @param encoding the text encoding type
     * @return index of the first null terminator, or -1 if not found
     */
    public static int indexOfTerminatorForEncoding(byte[] bytes, int fromIndex, int encoding) {
        int terminatorLength =
                (encoding == EncodedText.TEXT_ENCODING_UTF_16
                                || encoding == EncodedText.TEXT_ENCODING_UTF_16BE)
                        ? 2
                        : 1;
        return indexOfTerminator(bytes, fromIndex, terminatorLength);
    }
}
