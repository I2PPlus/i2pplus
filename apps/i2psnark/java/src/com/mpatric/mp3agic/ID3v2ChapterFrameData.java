package com.mpatric.mp3agic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Data of an ID3v2 chapter frame. */
public class ID3v2ChapterFrameData extends AbstractID3v2FrameData {

    /** The chapter id text. */
    protected String id;
    /** Chapter start time, in ms. */
    protected int startTime;
    /** Chapter end time, in ms. */
    protected int endTime;
    /** Chapter start offset. */
    protected int startOffset;
    /** Chapter end offset. */
    protected int endOffset;
    /** The chapter's subframes. */
    protected ArrayList<ID3v2Frame> subframes = new ArrayList<>();

    /**
     * Builds an empty chapter frame body.
     *
     * @param unsynchronisation true if the frame is unsynchronised
     */
    public ID3v2ChapterFrameData(boolean unsynchronisation) {
        super(unsynchronisation);
    }

    /**
     * Builds a chapter frame body from the field values.
     *
     * @param unsynchronisation true if the frame is unsynchronised
     * @param id the chapter id text
     * @param startTime the start time, in ms
     * @param endTime the end time, in ms
     * @param startOffset the start offset
     * @param endOffset the end offset
     */
    public ID3v2ChapterFrameData(
            boolean unsynchronisation,
            String id,
            int startTime,
            int endTime,
            int startOffset,
            int endOffset) {
        super(unsynchronisation);
        this.id = id;
        this.startTime = startTime;
        this.endTime = endTime;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    /**
     * Parses a chapter frame body from the frame bytes.
     *
     * @param unsynchronisation true if the frame is unsynchronised
     * @param bytes the packed frame body
     * @throws InvalidDataException if the bytes are not a chapter frame
     */
    public ID3v2ChapterFrameData(boolean unsynchronisation, byte[] bytes)
            throws InvalidDataException {
        super(unsynchronisation);
        synchroniseAndUnpackFrameData(bytes);
    }

    @Override
    protected void unpackFrameData(byte[] bytes) throws InvalidDataException {
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        id = ByteBufferUtils.extractNullTerminatedString(bb);

        bb.position(id.length() + 1);
        startTime = bb.getInt();
        endTime = bb.getInt();
        startOffset = bb.getInt();
        endOffset = bb.getInt();

        for (int offset = bb.position(); offset < bytes.length; ) {
            ID3v2Frame frame = new ID3v2Frame(bytes, offset);
            offset += frame.getLength();
            subframes.add(frame);
        }
    }

    /**
     * Adds one subframe to the chapter.
     *
     * @param id the subframe id
     * @param frame the subframe data
     */
    public void addSubframe(String id, AbstractID3v2FrameData frame) {
        subframes.add(new ID3v2Frame(id, frame.toBytes()));
    }

    @Override
    protected byte[] packFrameData() {
        ByteBuffer bb = ByteBuffer.allocate(getLength());
        bb.put(id.getBytes(StandardCharsets.ISO_8859_1));
        bb.put((byte) 0);

        bb.putInt(startTime);
        bb.putInt(endTime);
        bb.putInt(startOffset);
        bb.putInt(endOffset);

        for (ID3v2Frame frame : subframes) {
            try {
                bb.put(frame.toBytes());
            } catch (NotSupportedException e) {
                e.printStackTrace();
            }
        }
        return bb.array();
    }

    /**
     * Returns the chapter id text.
     *
     * @return the chapter id text
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the chapter id text.
     *
     * @param id the id to set
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the chapter start time.
     *
     * @return the time, in ms
     */
    public int getStartTime() {
        return startTime;
    }

    /**
     * Sets the chapter start time.
     *
     * @param startTime the time, in ms
     */
    public void setStartTime(int startTime) {
        this.startTime = startTime;
    }

    /**
     * Returns the chapter end time.
     *
     * @return the time, in ms
     */
    public int getEndTime() {
        return endTime;
    }

    /**
     * Sets the chapter end time.
     *
     * @param endTime the time, in ms
     */
    public void setEndTime(int endTime) {
        this.endTime = endTime;
    }

    /**
     * Returns the chapter start offset.
     *
     * @return the start offset
     */
    public int getStartOffset() {
        return startOffset;
    }

    /**
     * Sets the chapter start offset.
     *
     * @param startOffset the start offset
     */
    public void setStartOffset(int startOffset) {
        this.startOffset = startOffset;
    }

    /**
     * Returns the chapter end offset.
     *
     * @return the end offset
     */
    public int getEndOffset() {
        return endOffset;
    }

    /**
     * Sets the chapter end offset.
     *
     * @param endOffset the end offset
     */
    public void setEndOffset(int endOffset) {
        this.endOffset = endOffset;
    }

    /**
     * Returns the chapter's subframes.
     *
     * @return the chapter's subframes
     */
    public ArrayList<ID3v2Frame> getSubframes() {
        return subframes;
    }

    /**
     * Sets the chapter's subframes.
     *
     * @param subframes the subframes to set
     */
    public void setSubframes(ArrayList<ID3v2Frame> subframes) {
        this.subframes = subframes;
    }

    @Override
    protected int getLength() {
        int length = 1;
        length += 16;
        if (id != null) length += id.length();
        if (subframes != null) {
            for (ID3v2Frame frame : subframes) {
                length += frame.getLength();
            }
        }
        return length;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ID3v2ChapterFrameData [id=");
        builder.append(id);
        builder.append(", startTime=");
        builder.append(startTime);
        builder.append(", endTime=");
        builder.append(endTime);
        builder.append(", startOffset=");
        builder.append(startOffset);
        builder.append(", endOffset=");
        builder.append(endOffset);
        builder.append(", subframes=");
        builder.append(subframes);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + endOffset;
        result = prime * result + endTime;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + startOffset;
        result = prime * result + startTime;
        result = prime * result + ((subframes == null) ? 0 : subframes.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!super.equals(obj)) return false;
        if (getClass() != obj.getClass()) return false;
        ID3v2ChapterFrameData other = (ID3v2ChapterFrameData) obj;
        if (endOffset != other.endOffset) return false;
        if (endTime != other.endTime) return false;
        if (id == null) {
            if (other.id != null) return false;
        } else if (!id.equals(other.id)) return false;
        if (startOffset != other.startOffset) return false;
        if (startTime != other.startTime) return false;
        if (subframes == null) {
            if (other.subframes != null) return false;
        } else if (!subframes.equals(other.subframes)) return false;
        return true;
    }
}
