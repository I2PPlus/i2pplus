package com.mpatric.mp3agic;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/** Reads and writes MP3 files with their ID3 tags. */
public class Mp3File extends FileWrapper {

    /** Default read buffer size, in bytes. */
    private static final int DEFAULT_BUFFER_LENGTH = 65536;
    /** Smallest read buffer size allowed. */
    private static final int MINIMUM_BUFFER_LENGTH = 40;
    /** First candidate offset of the Xing marker. */
    private static final int XING_MARKER_OFFSET_1 = 13;
    /** Second candidate offset of the Xing marker. */
    private static final int XING_MARKER_OFFSET_2 = 21;
    /** Third candidate offset of the Xing marker. */
    private static final int XING_MARKER_OFFSET_3 = 36;

    /** Read buffer size, in bytes. */
    protected int bufferLength;
    /** Offset of the Xing header, -1 if absent. */
    private int xingOffset = -1;
    /** Offset of the first MPEG frame. */
    private int startOffset = -1;
    /** Offset just past the last MPEG frame. */
    private int endOffset = -1;
    /** Number of MPEG frames found. */
    private int frameCount = 0;
    /** Counts of frames at each bitrate. */
    private Map<Integer, MutableInteger> bitrates = new HashMap<>();
    /** Bitrate reported by the Xing header. */
    private int xingBitrate;
    /** Average bitrate of the file, in bps. */
    private double bitrate = 0;
    /** Channel mode of the first frame. */
    private String channelMode;
    /** Emphasis setting of the first frame. */
    private String emphasis;
    /** MPEG layer of the first frame. */
    private String layer;
    /** Mode extension of the first frame. */
    private String modeExtension;
    /** Sample rate of the first frame, in Hz. */
    private int sampleRate;
    /** Whether the frames are marked copyrighted. */
    private boolean copyright;
    /** Whether the frames are marked original. */
    private boolean original;
    /** MPEG version of the first frame. */
    private String version;
    /** The ID3v1 tag, if present. */
    private ID3v1 id3v1Tag;
    /** The ID3v2 tag, if present. */
    private ID3v2 id3v2Tag;
    /** Bytes of a custom tag, if present. */
    private byte[] customTag;
    /** Whether every frame is scanned on load. */
    private boolean scanFile;

    /** Constructor leaving the file unloaded. */
    protected Mp3File() {}

    /**
     * Creates an Mp3File instance from the specified filename with default buffer length and scanning enabled.
     *
     * @param filename the path to the MP3 file
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(String filename)
            throws IOException, UnsupportedTagException, InvalidDataException {
        this(filename, DEFAULT_BUFFER_LENGTH, true);
    }

    /**
     * Creates an Mp3File instance from the specified filename with custom buffer length and scanning enabled.
     *
     * @param filename the path to the MP3 file
     * @param bufferLength the buffer size to use for reading the file
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(String filename, int bufferLength)
            throws IOException, UnsupportedTagException, InvalidDataException {
        this(filename, bufferLength, true);
    }

    /**
     * Creates an Mp3File instance from the specified filename with default buffer length and optional scanning.
     *
     * @param filename the path to the MP3 file
     * @param scanFile whether to scan the entire file for accurate bitrate and duration information
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(String filename, boolean scanFile)
            throws IOException, UnsupportedTagException, InvalidDataException {
        this(filename, DEFAULT_BUFFER_LENGTH, scanFile);
    }

    /**
     * Creates an Mp3File instance from the specified filename with custom buffer length and scanning option.
     *
     * @param filename the path to the MP3 file
     * @param bufferLength the buffer size to use for reading the file
     * @param scanFile whether to scan the entire file for accurate bitrate and duration information
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(String filename, int bufferLength, boolean scanFile)
            throws IOException, UnsupportedTagException, InvalidDataException {
        super(filename);
        init(bufferLength, scanFile);
    }

    /**
     * Creates an Mp3File instance from the specified File object with default buffer length and scanning enabled.
     *
     * @param file the MP3 file to read
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(File file) throws IOException, UnsupportedTagException, InvalidDataException {
        this(file, DEFAULT_BUFFER_LENGTH, true);
    }

    /**
     * Creates an Mp3File instance from the specified File object with custom buffer length and scanning enabled.
     *
     * @param file the MP3 file to read
     * @param bufferLength the buffer size to use for reading the file
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(File file, int bufferLength)
            throws IOException, UnsupportedTagException, InvalidDataException {
        this(file, bufferLength, true);
    }

    /**
     * Creates an Mp3File instance from the specified File object with custom buffer length and scanning option.
     *
     * @param file the MP3 file to read
     * @param bufferLength the buffer size to use for reading the file
     * @param scanFile whether to scan the entire file for accurate bitrate and duration information
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(File file, int bufferLength, boolean scanFile)
            throws IOException, UnsupportedTagException, InvalidDataException {
        super(file);
        init(bufferLength, scanFile);
    }

    /**
     * Creates an Mp3File instance from the specified Path object with default buffer length and scanning enabled.
     *
     * @param path the path to the MP3 file
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(Path path) throws IOException, UnsupportedTagException, InvalidDataException {
        this(path, DEFAULT_BUFFER_LENGTH, true);
    }

    /**
     * Creates an Mp3File instance from the specified Path object with custom buffer length and scanning enabled.
     *
     * @param path the path to the MP3 file
     * @param bufferLength the buffer size to use for reading the file
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(Path path, int bufferLength)
            throws IOException, UnsupportedTagException, InvalidDataException {
        this(path, bufferLength, true);
    }

    /**
     * Creates an Mp3File instance from the specified Path object with custom buffer length and scanning option.
     *
     * @param path the path to the MP3 file
     * @param bufferLength the buffer size to use for reading the file
     * @param scanFile whether to scan the entire file for accurate bitrate and duration information
     * @throws IOException if an I/O error occurs while reading the file
     * @throws UnsupportedTagException if the file contains an unsupported ID3 tag version
     * @throws InvalidDataException if the file contains invalid MP3 data
     */
    public Mp3File(Path path, int bufferLength, boolean scanFile)
            throws IOException, UnsupportedTagException, InvalidDataException {
        super(path);
        init(bufferLength, scanFile);
    }

    /**
     * Reads the header and the tags of the file.
     *
     * @param bufferLength the size of the read buffer
     * @param scanFile true to scan the whole file for frames
     * @throws IOException if the file cannot be read
     * @throws UnsupportedTagException if the tag version is unknown
     * @throws InvalidDataException if the tag data is corrupt
     */
    private void init(int bufferLength, boolean scanFile)
            throws IOException, UnsupportedTagException, InvalidDataException {
        if (bufferLength < MINIMUM_BUFFER_LENGTH + 1)
            throw new IllegalArgumentException("Buffer too small");

        this.bufferLength = bufferLength;
        this.scanFile = scanFile;

        try (SeekableByteChannel seekableByteChannel =
                Files.newByteChannel(path, StandardOpenOption.READ)) {
            initId3v1Tag(seekableByteChannel);
            scanFile(seekableByteChannel);
            if (startOffset < 0) {
                throw new InvalidDataException("No mpegs frames found");
            }
            initId3v2Tag(seekableByteChannel);
            if (scanFile) {
                initCustomTag(seekableByteChannel);
            }
        }
    }

    /**
     * Finds where the MPEG frames start after any tags.
     *
     * @param seekableByteChannel the file to scan
     * @return the offset of the first frame area
     */
    protected int preScanFile(SeekableByteChannel seekableByteChannel) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(AbstractID3v2Tag.HEADER_LENGTH);
        try {
            seekableByteChannel.position(0);
            byteBuffer.clear();
            int bytesRead = seekableByteChannel.read(byteBuffer);
            if (bytesRead == AbstractID3v2Tag.HEADER_LENGTH) {
                try {
                    byte[] bytes = byteBuffer.array();
                    ID3v2TagFactory.sanityCheckTag(bytes);
                    return AbstractID3v2Tag.HEADER_LENGTH
                            + BufferTools.unpackSynchsafeInteger(
                                    bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET],
                                    bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 1],
                                    bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 2],
                                    bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 3]);
                } catch (NoSuchTagException | UnsupportedTagException e) {
                    // do nothing
                }
            }
        } catch (IOException e) {
            // do nothing
        }
        return 0;
    }

    /**
     * Scans the whole file for mpeg frames.
     *
     * @param seekableByteChannel the file to scan
     * @throws IOException if the file cannot be read
     * @throws InvalidDataException if a frame is corrupt
     */
    private void scanFile(SeekableByteChannel seekableByteChannel)
            throws IOException, InvalidDataException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(bufferLength);
        int fileOffset = preScanFile(seekableByteChannel);
        seekableByteChannel.position(fileOffset);
        boolean lastBlock = false;
        int lastOffset = fileOffset;
        while (!lastBlock) {
            byteBuffer.clear();
            int bytesRead = seekableByteChannel.read(byteBuffer);
            byte[] bytes = byteBuffer.array();
            if (bytesRead < bufferLength) lastBlock = true;
            if (bytesRead >= MINIMUM_BUFFER_LENGTH) {
                while (true) {
                    try {
                        int offset = 0;
                        if (startOffset < 0) {
                            offset = scanBlockForStart(bytes, bytesRead, fileOffset, offset);
                            if (startOffset >= 0 && !scanFile) {
                                return;
                            }
                            lastOffset = startOffset;
                        }
                        offset = scanBlock(bytes, bytesRead, fileOffset, offset);
                        fileOffset += offset;
                        seekableByteChannel.position(fileOffset);
                        break;
                    } catch (InvalidDataException e) {
                        if (frameCount < 2) {
                            startOffset = -1;
                            xingOffset = -1;
                            frameCount = 0;
                            bitrates.clear();
                            lastBlock = false;
                            fileOffset = lastOffset + 1;
                            if (fileOffset == 0)
                                throw new InvalidDataException(
                                        "Valid start of mpeg frames not found", e);
                            seekableByteChannel.position(fileOffset);
                            break;
                        }
                        return;
                    }
                }
            }
        }
    }

    /**
     * Finds the first valid frame in a block.
     *
     * @param bytes the block read
     * @param bytesRead the valid byte count in the block
     * @param absoluteOffset the file offset of the block start
     * @param offset the position within the block
     * @return the file offset of the first frame, or -1
     */
    private int scanBlockForStart(byte[] bytes, int bytesRead, int absoluteOffset, int offset) {
        while (offset < bytesRead - MINIMUM_BUFFER_LENGTH) {
            if (bytes[offset] == (byte) 0xFF && (bytes[offset + 1] & (byte) 0xE0) == (byte) 0xE0) {
                try {
                    MpegFrame frame =
                            new MpegFrame(
                                    bytes[offset],
                                    bytes[offset + 1],
                                    bytes[offset + 2],
                                    bytes[offset + 3]);
                    if (xingOffset < 0 && isXingFrame(bytes, offset)) {
                        xingOffset = absoluteOffset + offset;
                        xingBitrate = frame.getBitrate();
                        offset += frame.getLengthInBytes();
                    } else {
                        startOffset = absoluteOffset + offset;
                        channelMode = frame.getChannelMode();
                        emphasis = frame.getEmphasis();
                        layer = frame.getLayer();
                        modeExtension = frame.getModeExtension();
                        sampleRate = frame.getSampleRate();
                        version = frame.getVersion();
                        copyright = frame.isCopyright();
                        original = frame.isOriginal();
                        frameCount++;
                        addBitrate(frame.getBitrate());
                        offset += frame.getLengthInBytes();
                        return offset;
                    }
                } catch (InvalidDataException e) {
                    offset++;
                }
            } else {
                offset++;
            }
        }
        return offset;
    }

    /**
     * Scans one block for mpeg frames.
     *
     * @param bytes the bytes of the block
     * @param bytesRead how many bytes are valid
     * @param absoluteOffset where the block starts in the file
     * @param offset where to start in the block
     * @return the offset reached in the block
     * @throws InvalidDataException if a frame is corrupt
     */
    private int scanBlock(byte[] bytes, int bytesRead, int absoluteOffset, int offset)
            throws InvalidDataException {
        while (offset < bytesRead - MINIMUM_BUFFER_LENGTH) {
            MpegFrame frame =
                    new MpegFrame(
                            bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
            sanityCheckFrame(frame, absoluteOffset + offset);
            int newEndOffset = absoluteOffset + offset + frame.getLengthInBytes() - 1;
            if (newEndOffset < maxEndOffset()) {
                endOffset = absoluteOffset + offset + frame.getLengthInBytes() - 1;
                frameCount++;
                addBitrate(frame.getBitrate());
                offset += frame.getLengthInBytes();
            } else {
                break;
            }
        }
        return offset;
    }

    /**
     * Returns the largest offset a frame scan can reach.
     *
     * @return the end offset limit
     */
    private int maxEndOffset() {
        int maxEndOffset = (int) getLength();
        if (hasId3v1Tag()) maxEndOffset -= ID3v1Tag.TAG_LENGTH;
        return maxEndOffset;
    }

    /**
     * Checks whether the frame holds a Xing header.
     *
     * @param bytes the block read
     * @param offset the position within the block
     * @return true if the Xing marker is present
     */
    private boolean isXingFrame(byte[] bytes, int offset) {
        if (bytes.length >= offset + XING_MARKER_OFFSET_1 + 3) {
            if ("Xing"
                    .equals(
                            BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                    bytes, offset + XING_MARKER_OFFSET_1, 4))) return true;
            if ("Info"
                    .equals(
                            BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                    bytes, offset + XING_MARKER_OFFSET_1, 4))) return true;
            if (bytes.length >= offset + XING_MARKER_OFFSET_2 + 3) {
                if ("Xing"
                        .equals(
                                BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                        bytes, offset + XING_MARKER_OFFSET_2, 4))) return true;
                if ("Info"
                        .equals(
                                BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                        bytes, offset + XING_MARKER_OFFSET_2, 4))) return true;
                if (bytes.length >= offset + XING_MARKER_OFFSET_3 + 3) {
                    if ("Xing"
                            .equals(
                                    BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                            bytes, offset + XING_MARKER_OFFSET_3, 4))) return true;
                    if ("Info"
                            .equals(
                                    BufferTools.byteBufferToStringIgnoringEncodingIssues(
                                            bytes, offset + XING_MARKER_OFFSET_3, 4))) return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks a frame against the first frame's properties.
     *
     * @param frame the frame to check
     * @param offset the frame's file offset
     * @throws InvalidDataException if the frame is inconsistent
     */
    private void sanityCheckFrame(MpegFrame frame, int offset) throws InvalidDataException {
        if (sampleRate != frame.getSampleRate())
            throw new InvalidDataException("Inconsistent frame header");
        if (!layer.equals(frame.getLayer()))
            throw new InvalidDataException("Inconsistent frame header");
        if (!version.equals(frame.getVersion()))
            throw new InvalidDataException("Inconsistent frame header");
        if (offset + frame.getLengthInBytes() > getLength())
            throw new InvalidDataException("Frame would extend beyond end of file");
    }

    /**
     * Counts one frame at the given bitrate.
     *
     * @param bitrate the frame bitrate, in bps
     */
    private void addBitrate(final int bitrate) {
        MutableInteger count = bitrates.get(bitrate);
        if (count != null) {
            count.increment();
        } else {
            bitrates.put(bitrate, new MutableInteger(1));
        }
        this.bitrate = ((this.bitrate * (frameCount - 1)) + bitrate) / frameCount;
    }

    /**
     * Reads the ID3v1 tag from the end of the file.
     *
     * @param seekableByteChannel the file to read
     * @throws IOException if the file cannot be read
     */
    private void initId3v1Tag(SeekableByteChannel seekableByteChannel) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(ID3v1Tag.TAG_LENGTH);
        seekableByteChannel.position(getLength() - ID3v1Tag.TAG_LENGTH);
        byteBuffer.clear();
        int bytesRead = seekableByteChannel.read(byteBuffer);
        if (bytesRead < ID3v1Tag.TAG_LENGTH) throw new IOException("Not enough bytes read");
        try {
            id3v1Tag = new ID3v1Tag(byteBuffer.array());
        } catch (NoSuchTagException e) {
            id3v1Tag = null;
        }
    }

    /**
     * Reads the ID3v2 tag at the file start.
     *
     * @param seekableByteChannel the file to read
     * @throws IOException if the file cannot be read
     * @throws UnsupportedTagException if the tag version is unknown
     * @throws InvalidDataException if the tag data is corrupt
     */
    private void initId3v2Tag(SeekableByteChannel seekableByteChannel)
            throws IOException, UnsupportedTagException, InvalidDataException {
        if (xingOffset == 0 || startOffset == 0) {
            id3v2Tag = null;
        } else {
            int bufferLength;
            if (hasXingFrame()) bufferLength = xingOffset;
            else bufferLength = startOffset;
            ByteBuffer byteBuffer = ByteBuffer.allocate(bufferLength);
            seekableByteChannel.position(0);
            byteBuffer.clear();
            int bytesRead = seekableByteChannel.read(byteBuffer);
            if (bytesRead < bufferLength) throw new IOException("Not enough bytes read");
            try {
                id3v2Tag = ID3v2TagFactory.createTag(byteBuffer.array());
            } catch (NoSuchTagException e) {
                id3v2Tag = null;
            }
        }
    }

    /**
     * Reads any custom tag before the MPEG frames.
     *
     * @param seekableByteChannel the file to read
     * @throws IOException if the file cannot be read
     */
    private void initCustomTag(SeekableByteChannel seekableByteChannel) throws IOException {
        int bufferLength = (int) (getLength() - (endOffset + 1));
        if (hasId3v1Tag()) bufferLength -= ID3v1Tag.TAG_LENGTH;
        if (bufferLength <= 0) {
            customTag = null;
        } else {
            ByteBuffer byteBuffer = ByteBuffer.allocate(bufferLength);
            seekableByteChannel.position(endOffset + 1);
            byteBuffer.clear();
            int bytesRead = seekableByteChannel.read(byteBuffer);
            customTag = byteBuffer.array();
            if (bytesRead < bufferLength) throw new IOException("Not enough bytes read");
        }
    }

    /**
     * Returns the number of MPEG frames in the MP3 file.
     *
     * @return the frame count
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * Returns the byte offset of the first MPEG frame in the file.
     *
     * @return the start offset in bytes
     */
    public int getStartOffset() {
        return startOffset;
    }

    /**
     * Returns the byte offset of the last MPEG frame in the file.
     *
     * @return the end offset in bytes
     */
    public int getEndOffset() {
        return endOffset;
    }

    /**
     * Returns the duration of the MP3 file in milliseconds.
     *
     * @return the duration in milliseconds
     */
    public long getLengthInMilliseconds() {
        return (long) (((endOffset - startOffset) * (8.0 / bitrate)) + 0.5);
    }

    /**
     * Returns the duration of the MP3 file in seconds.
     *
     * @return the duration in seconds
     */
    public long getLengthInSeconds() {
        return ((getLengthInMilliseconds() + 500) / 1000);
    }

    /**
     * Returns whether the MP3 file uses variable bitrate (VBR).
     *
     * @return true if the file is VBR, false if CBR
     */
    public boolean isVbr() {
        return bitrates.size() > 1;
    }

    /**
     * Returns the average bitrate of the MP3 file.
     *
     * @return the average bitrate in kbps
     */
    public int getBitrate() {
        return (int) (bitrate + 0.5);
    }

    /**
     * Returns a map of all bitrates found in the file and their occurrence counts.
     *
     * @return a map where keys are bitrates and values are occurrence counts
     */
    public Map<Integer, MutableInteger> getBitrates() {
        return bitrates;
    }

    /**
     * Returns the channel mode of the MP3 file.
     *
     * @return the channel mode (e.g., "Stereo", "Joint Stereo", "Mono", etc.)
     */
    public String getChannelMode() {
        return channelMode;
    }

    /**
     * Returns whether the MP3 file is marked as copyrighted.
     *
     * @return true if the file is copyrighted, false otherwise
     */
    public boolean isCopyright() {
        return copyright;
    }

    /**
     * Returns the emphasis used in the MP3 file.
     *
     * @return the emphasis type (e.g., "None", "50/15 ms", etc.)
     */
    public String getEmphasis() {
        return emphasis;
    }

    /**
     * Returns the MPEG layer of the MP3 file.
     *
     * @return the layer (e.g., "Layer I", "Layer II", "Layer III")
     */
    public String getLayer() {
        return layer;
    }

    /**
     * Returns the mode extension for joint stereo channels.
     *
     * @return the mode extension
     */
    public String getModeExtension() {
        return modeExtension;
    }

    /**
     * Returns whether the MP3 file is marked as original.
     *
     * @return true if the file is original, false otherwise
     */
    public boolean isOriginal() {
        return original;
    }

    /**
     * Returns the sample rate of the MP3 file.
     *
     * @return the sample rate in Hz
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the MPEG version of the MP3 file.
     *
     * @return the version (e.g., "MPEG Version 1", "MPEG Version 2", "MPEG Version 2.5")
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns whether the MP3 file contains a Xing frame.
     *
     * @return true if the file has a Xing frame, false otherwise
     */
    public boolean hasXingFrame() {
        return (xingOffset >= 0);
    }

    /**
     * Returns the byte offset of the Xing frame in the file.
     *
     * @return the Xing frame offset in bytes, or -1 if no Xing frame exists
     */
    public int getXingOffset() {
        return xingOffset;
    }

    /**
     * Returns the bitrate from the Xing frame.
     *
     * @return the Xing frame bitrate in kbps
     */
    public int getXingBitrate() {
        return xingBitrate;
    }

    /**
     * Returns whether the MP3 file contains an ID3v1 tag.
     *
     * @return true if the file has an ID3v1 tag, false otherwise
     */
    public boolean hasId3v1Tag() {
        return id3v1Tag != null;
    }

    /**
     * Returns the ID3v1 tag from the MP3 file.
     *
     * @return the ID3v1 tag, or null if no ID3v1 tag exists
     */
    public ID3v1 getId3v1Tag() {
        return id3v1Tag;
    }

    /**
     * Sets the ID3v1 tag for the MP3 file.
     *
     * @param id3v1Tag the ID3v1 tag to set, or null to remove
     */
    public void setId3v1Tag(ID3v1 id3v1Tag) {
        this.id3v1Tag = id3v1Tag;
    }

    /**
     * Removes the ID3v1 tag from the MP3 file.
     */
    public void removeId3v1Tag() {
        this.id3v1Tag = null;
    }

    /**
     * Returns whether the MP3 file contains an ID3v2 tag.
     *
     * @return true if the file has an ID3v2 tag, false otherwise
     */
    public boolean hasId3v2Tag() {
        return id3v2Tag != null;
    }

    /**
     * Returns the ID3v2 tag from the MP3 file.
     *
     * @return the ID3v2 tag, or null if no ID3v2 tag exists
     */
    public ID3v2 getId3v2Tag() {
        return id3v2Tag;
    }

    /**
     * Sets the ID3v2 tag for the MP3 file.
     *
     * @param id3v2Tag the ID3v2 tag to set, or null to remove
     */
    public void setId3v2Tag(ID3v2 id3v2Tag) {
        this.id3v2Tag = id3v2Tag;
    }

    /**
     * Removes the ID3v2 tag from the MP3 file.
     */
    public void removeId3v2Tag() {
        this.id3v2Tag = null;
    }

    /**
     * Returns whether the MP3 file contains a custom tag.
     *
     * @return true if the file has a custom tag, false otherwise
     */
    public boolean hasCustomTag() {
        return customTag != null;
    }

    /**
     * Returns the custom tag data from the MP3 file.
     *
     * @return the custom tag as a byte array, or null if no custom tag exists
     */
    public byte[] getCustomTag() {
        return customTag;
    }

    /**
     * Sets the custom tag for the MP3 file.
     *
     * @param customTag the custom tag data as a byte array, or null to remove
     */
    public void setCustomTag(byte[] customTag) {
        this.customTag = customTag;
    }

    /**
     * Removes the custom tag from the MP3 file.
     */
    public void removeCustomTag() {
        this.customTag = null;
    }

    /**
     * Saves the MP3 file with its current tags to a new filename.
     *
     * @param newFilename the filename to save the MP3 file to
     * @throws IOException if an I/O error occurs while writing the file
     * @throws NotSupportedException if the operation is not supported
     * @throws IllegalArgumentException if the save filename is the same as the source filename
     */
    public void save(String newFilename) throws IOException, NotSupportedException {
        if (path.toAbsolutePath().compareTo(Paths.get(newFilename).toAbsolutePath()) == 0) {
            throw new IllegalArgumentException("Save filename same as source filename");
        }
        try (SeekableByteChannel saveFile =
                Files.newByteChannel(
                        Paths.get(newFilename),
                        EnumSet.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE))) {
            if (hasId3v2Tag()) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(id3v2Tag.toBytes());
                byteBuffer.rewind();
                saveFile.write(byteBuffer);
            }
            saveMpegFrames(saveFile);
            if (hasCustomTag()) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(customTag);
                byteBuffer.rewind();
                saveFile.write(byteBuffer);
            }
            if (hasId3v1Tag()) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(id3v1Tag.toBytes());
                byteBuffer.rewind();
                saveFile.write(byteBuffer);
            }
            saveFile.close();
        }
    }

    /**
     * Copies the MPEG frames to the save file.
     *
     * @param saveFile the file to write
     * @throws IOException if the file cannot be written
     */
    private void saveMpegFrames(SeekableByteChannel saveFile) throws IOException {
        int filePos = xingOffset;
        if (filePos < 0) filePos = startOffset;
        if (filePos < 0) return;
        if (endOffset < filePos) return;
        ByteBuffer byteBuffer = ByteBuffer.allocate(bufferLength);
        try (SeekableByteChannel seekableByteChannel =
                Files.newByteChannel(path, StandardOpenOption.READ)) {
            seekableByteChannel.position(filePos);
            while (true) {
                byteBuffer.clear();
                int bytesRead = seekableByteChannel.read(byteBuffer);
                byteBuffer.rewind();
                if (filePos + bytesRead <= endOffset) {
                    byteBuffer.limit(bytesRead);
                    saveFile.write(byteBuffer);
                    filePos += bytesRead;
                } else {
                    byteBuffer.limit(endOffset - filePos + 1);
                    saveFile.write(byteBuffer);
                    break;
                }
            }
        }
    }
}
