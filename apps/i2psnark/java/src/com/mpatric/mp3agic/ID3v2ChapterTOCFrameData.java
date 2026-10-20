package com.mpatric.mp3agic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** Table of contents frame data for ID3v2 chapter and table-of-contents frames. */
public class ID3v2ChapterTOCFrameData extends AbstractID3v2FrameData {

    /** True if this is the root table of contents. */
    protected boolean isRoot;
    /** True if the child elements are ordered. */
    protected boolean isOrdered;
    /** The element ID of this table of contents. */
    protected String id;
    /** The element IDs of the children of this table of contents. */
    protected String[] children;
    /** The subframes contained in this frame. */
    protected ArrayList<ID3v2Frame> subframes = new ArrayList<>();

        /**
         * Create an empty chapter table of contents frame.
         *
         * @param unsynchronisation whether the frame uses unsynchronisation
         */
    public ID3v2ChapterTOCFrameData(boolean unsynchronisation) {
        super(unsynchronisation);
    }

        /**
         * Create a chapter table of contents frame with the given values.
         *
         * @param unsynchronisation whether the frame uses unsynchronisation
         * @param isRoot true if this is the root table of contents
         * @param isOrdered true if the child elements are ordered
         * @param id the element ID of this table of contents
         * @param children the element IDs of the children
         */
    public ID3v2ChapterTOCFrameData(
            boolean unsynchronisation,
            boolean isRoot,
            boolean isOrdered,
            String id,
            String[] children) {
        super(unsynchronisation);
        this.isRoot = isRoot;
        this.isOrdered = isOrdered;
        this.id = id;
        this.children = children;
    }

        /**
         * Create a chapter table of contents frame from the given frame bytes.
         *
         * @param unsynchronisation whether the frame uses unsynchronisation
         * @param bytes the raw frame bytes
         * @throws InvalidDataException if the bytes cannot be parsed
         */
    public ID3v2ChapterTOCFrameData(boolean unsynchronisation, byte[] bytes)
            throws InvalidDataException {
        super(unsynchronisation);
        synchroniseAndUnpackFrameData(bytes);
    }

    @Override
    protected void unpackFrameData(byte[] bytes) throws InvalidDataException {
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        id = ByteBufferUtils.extractNullTerminatedString(bb);

        byte flags = bb.get();
        if ((flags & 0x01) == 0x01) {
            isRoot = true;
        }
        if ((flags & 0x02) == 0x02) {
            isOrdered = true;
        }

        int childCount = bb.get();

        children = new String[childCount];

        for (int i = 0; i < childCount; i++) {
            children[i] = ByteBufferUtils.extractNullTerminatedString(bb);
        }

        for (int offset = bb.position(); offset < bytes.length; ) {
            ID3v2Frame frame = new ID3v2Frame(bytes, offset);
            offset += frame.getLength();
            subframes.add(frame);
        }
    }

        /**
         * Add a subframe with the given ID and data.
         *
         * @param id the subframe ID
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
        bb.put(getFlags());
        bb.put((byte) children.length);

        for (String child : children) {
            bb.put(child.getBytes(StandardCharsets.ISO_8859_1));
            bb.put((byte) 0);
        }

        for (ID3v2Frame frame : subframes) {
            try {
                bb.put(frame.toBytes());
            } catch (NotSupportedException e) {
                e.printStackTrace();
            }
        }
        return bb.array();
    }

    private byte getFlags() {
        byte b = 0;

        if (isRoot) {
            b |= 0x01;
        }

        if (isOrdered) {
            b |= 0x02;
        }
        return b;
    }

    /**
     * Whether this is the root table of contents.
     *
     * @return true if this is the root table of contents
     */
    public boolean isRoot() {
        return isRoot;
    }

    /**
     * Sets whether this is the root table of contents.
     *
     * @param isRoot true if this is the root table of contents
     */
    public void setRoot(boolean isRoot) {
        this.isRoot = isRoot;
    }

    /**
     * Whether the child elements are ordered.
     *
     * @return true if the child elements are ordered
     */
    public boolean isOrdered() {
        return isOrdered;
    }

    /**
     * Sets whether the child elements are ordered.
     *
     * @param isOrdered true if the child elements are ordered
     */
    public void setOrdered(boolean isOrdered) {
        this.isOrdered = isOrdered;
    }

    /**
     * The element ID of this table of contents.
     *
     * @return the element ID of this table of contents
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the element ID of this table of contents.
     *
     * @param id the element ID of this table of contents
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * The element IDs of the children of this table of contents.
     *
     * @return the element IDs of the children
     */
    public String[] getChildren() {
        return children;
    }

    /**
     * Sets the element IDs of the children of this table of contents.
     *
     * @param children the element IDs of the children
     */
    public void setChildren(String[] children) {
        this.children = children;
    }

    /**
     * The subframes contained in this frame.
     *
     * @return the subframes contained in this frame
     */
    public ArrayList<ID3v2Frame> getSubframes() {
        return subframes;
    }

    /**
     * Sets the subframes contained in this frame.
     *
     * @param subframes the subframes contained in this frame
     */
    public void setSubframes(ArrayList<ID3v2Frame> subframes) {
        this.subframes = subframes;
    }

    @Override
    protected int getLength() {
        int length = 3;
        if (id != null) length += id.length();
        if (children != null) {
            length += children.length;
            for (String child : children) {
                length += child.length();
            }
        }
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
        builder.append("ID3v2ChapterTOCFrameData [isRoot=");
        builder.append(isRoot);
        builder.append(", isOrdered=");
        builder.append(isOrdered);
        builder.append(", id=");
        builder.append(id);
        builder.append(", children=");
        builder.append(Arrays.toString(children));
        builder.append(", subframes=");
        builder.append(subframes);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + Arrays.hashCode(children);
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + (isOrdered ? 1231 : 1237);
        result = prime * result + (isRoot ? 1231 : 1237);
        result = prime * result + ((subframes == null) ? 0 : subframes.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!super.equals(obj)) return false;
        if (getClass() != obj.getClass()) return false;
        ID3v2ChapterTOCFrameData other = (ID3v2ChapterTOCFrameData) obj;
        if (!Arrays.equals(children, other.children)) return false;
        if (id == null) {
            if (other.id != null) return false;
        } else if (!id.equals(other.id)) return false;
        if (isOrdered != other.isOrdered) return false;
        if (isRoot != other.isRoot) return false;
        if (subframes == null) {
            if (other.subframes != null) return false;
        } else if (!subframes.equals(other.subframes)) return false;
        return true;
    }
}
