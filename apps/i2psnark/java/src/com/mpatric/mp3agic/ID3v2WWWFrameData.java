package com.mpatric.mp3agic;

import java.io.UnsupportedEncodingException;

/** The URL held by a WWW frame. */
public class ID3v2WWWFrameData extends AbstractID3v2FrameData {

    /** The frame's URL. */
    protected String url;

    /**
     * Creates an empty WWW frame.
     *
     * @param unsynchronisation whether the frame uses unsynchronisation
     */
    public ID3v2WWWFrameData(boolean unsynchronisation) {
        super(unsynchronisation);
    }

    /**
     * Creates a WWW frame with a given URL.
     *
     * @param unsynchronisation whether the frame uses unsynchronisation
     * @param url the frame's URL
     */
    public ID3v2WWWFrameData(boolean unsynchronisation, String url) {
        super(unsynchronisation);
        this.url = url;
    }

    /**
     * Reads a WWW frame from frame bytes.
     *
     * @param unsynchronisation whether the frame uses unsynchronisation
     * @param bytes the frame contents to parse
     * @throws InvalidDataException if the frame data is corrupt
     */
    public ID3v2WWWFrameData(boolean unsynchronisation, byte[] bytes) throws InvalidDataException {
        super(unsynchronisation);
        synchroniseAndUnpackFrameData(bytes);
    }

    @Override
    protected void unpackFrameData(byte[] bytes) throws InvalidDataException {
        try {
            url = BufferTools.byteBufferToString(bytes, 0, bytes.length);
        } catch (UnsupportedEncodingException e) {
            url = "";
        }
    }

    @Override
    protected byte[] packFrameData() {
        byte[] bytes = new byte[getLength()];
        if (url != null && url.length() > 0) {
            try {
                BufferTools.stringIntoByteBuffer(url, 0, url.length(), bytes, 0);
            } catch (UnsupportedEncodingException e) { /* ignored */ }
        }
        return bytes;
    }

    @Override
    protected int getLength() {
        int length = 0;
        if (url != null) length = url.length();
        return length;
    }

    /**
     * Reads the frame's URL.
     *
     * @return the URL, null if unset
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the frame's URL.
     *
     * @param url the URL to hold
     */
    public void setUrl(String url) {
        this.url = url;
    }
}
