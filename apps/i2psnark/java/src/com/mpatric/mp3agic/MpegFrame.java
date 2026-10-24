package com.mpatric.mp3agic;

/** One parsed MPEG audio frame header. */
public class MpegFrame {

    /** Name of MPEG version 1.0. */
    public static final String MPEG_VERSION_1_0 = "1.0";
    /** Name of MPEG version 2.0. */
    public static final String MPEG_VERSION_2_0 = "2.0";
    /** Name of MPEG version 2.5. */
    public static final String MPEG_VERSION_2_5 = "2.5";
    /** Name of MPEG layer I. */
    public static final String MPEG_LAYER_1 = "I";
    /** Name of MPEG layer II. */
    public static final String MPEG_LAYER_2 = "II";
    /** Name of MPEG layer III. */
    public static final String MPEG_LAYER_3 = "III";
    /** Layer names, indexed by layer number. */
    public static final String[] MPEG_LAYERS = {null, MPEG_LAYER_1, MPEG_LAYER_2, MPEG_LAYER_3};
    /** Name of the mono channel mode. */
    public static final String CHANNEL_MODE_MONO = "Mono";
    /** Name of the dual-mono channel mode. */
    public static final String CHANNEL_MODE_DUAL_MONO = "Dual mono";
    /** Name of the joint-stereo channel mode. */
    public static final String CHANNEL_MODE_JOINT_STEREO = "Joint stereo";
    /** Name of the stereo channel mode. */
    public static final String CHANNEL_MODE_STEREO = "Stereo";
    /** Name of the bands 4-31 joint-stereo extension. */
    public static final String MODE_EXTENSION_BANDS_4_31 = "Bands 4-31";
    /** Name of the bands 8-31 joint-stereo extension. */
    public static final String MODE_EXTENSION_BANDS_8_31 = "Bands 8-31";
    /** Name of the bands 12-31 joint-stereo extension. */
    public static final String MODE_EXTENSION_BANDS_12_31 = "Bands 12-31";
    /** Name of the bands 16-31 joint-stereo extension. */
    public static final String MODE_EXTENSION_BANDS_16_31 = "Bands 16-31";
    /** Name of the no-extension joint-stereo mode. */
    public static final String MODE_EXTENSION_NONE = "None";
    /** Name of the intensity-stereo extension. */
    public static final String MODE_EXTENSION_INTENSITY_STEREO = "Intensity stereo";
    /** Name of the mid-side stereo extension. */
    public static final String MODE_EXTENSION_M_S_STEREO = "M/S stereo";
    /** Name of the combined intensity and mid-side extension. */
    public static final String MODE_EXTENSION_INTENSITY_M_S_STEREO = "Intensity & M/S stereo";
    /** Name used when no mode extension applies. */
    public static final String MODE_EXTENSION_NA = "n/a";
    /** Name of the no-emphasis setting. */
    public static final String EMPHASIS_NONE = "None";
    /** Name of the 50/15 ms emphasis curve. */
    public static final String EMPHASIS__50_15_MS = "50/15 ms";
    /** Name of the CCITT J.17 emphasis curve. */
    public static final String EMPHASIS_CCITT_J_17 = "CCITT J.17";

    /** Size of a frame header, in bytes. */
    private static final int FRAME_DATA_LENGTH = 4;
    /** The 11-bit frame sync pattern value. */
    private static final int FRAME_SYNC = 0x7FF;
    /** Mask of the frame sync field. */
    private static final long BITMASK_FRAME_SYNC = 0xFFE00000L;
    /** Mask of the MPEG version field. */
    private static final long BITMASK_VERSION = 0x180000L;
    /** Mask of the layer field. */
    private static final long BITMASK_LAYER = 0x60000L;
    /** Mask of the CRC protection bit. */
    private static final long BITMASK_PROTECTION = 0x10000L;
    /** Mask of the bitrate index field. */
    private static final long BITMASK_BITRATE = 0xF000L;
    /** Mask of the sample rate index field. */
    private static final long BITMASK_SAMPLE_RATE = 0xC00L;
    /** Mask of the padding bit. */
    private static final long BITMASK_PADDING = 0x200L;
    /** Mask of the private bit. */
    private static final long BITMASK_PRIVATE = 0x100L;
    /** Mask of the channel mode field. */
    private static final long BITMASK_CHANNEL_MODE = 0xC0L;
    /** Mask of the mode extension field. */
    private static final long BITMASK_MODE_EXTENSION = 0x30L;
    /** Mask of the copyright bit. */
    private static final long BITMASK_COPYRIGHT = 0x8L;
    /** Mask of the original-media bit. */
    private static final long BITMASK_ORIGINAL = 0x4L;
    /** Mask of the emphasis field. */
    private static final long BITMASK_EMPHASIS = 0x3L;

    /** The MPEG version name. */
    private String version;
    /** The layer number, 1 to 3. */
    private int layer;
    /** True if the frame is CRC protected. */
    private boolean protection;
    /** The bitrate, in kbps. */
    private int bitrate;
    /** The sample rate, in Hz. */
    private int sampleRate;
    /** True if the frame carries a padding byte. */
    private boolean padding;
    /** The private bit as set by the encoder. */
    private boolean privat;
    /** The channel mode name. */
    private String channelMode;
    /** The mode extension name. */
    private String modeExtension;
    /** True if the encoder marked the audio copyrighted. */
    private boolean copyright;
    /** True if this is the original media, not a copy. */
    private boolean original;
    /** The emphasis curve name. */
    private String emphasis;

    /**
     * Parses a four-byte frame header.
     *
     * @param frameData the header bytes
     * @throws InvalidDataException if the bytes are not a valid header
     */
    public MpegFrame(byte[] frameData) throws InvalidDataException {
        if (frameData.length < FRAME_DATA_LENGTH)
            throw new InvalidDataException("Mpeg frame too short");
        long frameHeader =
                BufferTools.unpackInteger(frameData[0], frameData[1], frameData[2], frameData[3]);
        setFields(frameHeader);
    }

    /**
     * Builds a frame from its four header bytes.
     *
     * @param frameData1 the first header byte
     * @param frameData2 the second header byte
     * @param frameData3 the third header byte
     * @param frameData4 the fourth header byte
     * @throws InvalidDataException if the header is invalid
     */
    public MpegFrame(byte frameData1, byte frameData2, byte frameData3, byte frameData4)
            throws InvalidDataException {
        long frameHeader =
                BufferTools.unpackInteger(frameData1, frameData2, frameData3, frameData4);
        setFields(frameHeader);
    }

    /** Creates an unset frame for a subclass to fill in. */
    protected MpegFrame() {}

    /**
     * Extracts every field from the header word.
     *
     * @param frameHeader the 32-bit header
     * @throws InvalidDataException if a field holds an invalid value
     */
    private void setFields(long frameHeader) throws InvalidDataException {
        long frameSync = extractField(frameHeader, BITMASK_FRAME_SYNC);
        if (frameSync != FRAME_SYNC) throw new InvalidDataException("Frame sync missing");
        setVersion(extractField(frameHeader, BITMASK_VERSION));
        setLayer(extractField(frameHeader, BITMASK_LAYER));
        setProtection(extractField(frameHeader, BITMASK_PROTECTION));
        setBitRate(extractField(frameHeader, BITMASK_BITRATE));
        setSampleRate(extractField(frameHeader, BITMASK_SAMPLE_RATE));
        setPadding(extractField(frameHeader, BITMASK_PADDING));
        setPrivate(extractField(frameHeader, BITMASK_PRIVATE));
        setChannelMode(extractField(frameHeader, BITMASK_CHANNEL_MODE));
        setModeExtension(extractField(frameHeader, BITMASK_MODE_EXTENSION));
        setCopyright(extractField(frameHeader, BITMASK_COPYRIGHT));
        setOriginal(extractField(frameHeader, BITMASK_ORIGINAL));
        setEmphasis(extractField(frameHeader, BITMASK_EMPHASIS));
    }

    /**
     * Pulls one field out of the header word.
     *
     * @param frameHeader the 32-bit header
     * @param bitMask the field's mask
     * @return the field's value
     */
    protected int extractField(long frameHeader, long bitMask) {
        int shiftBy = 0;
        for (int i = 0; i <= 31; i++) {
            if (((bitMask >> i) & 1) != 0) {
                shiftBy = i;
                break;
            }
        }
        return (int) ((frameHeader >> shiftBy) & (bitMask >> shiftBy));
    }

    /**
     * Sets the version from its header index.
     *
     * @param version the version index
     * @throws InvalidDataException if the index is reserved
     */
    private void setVersion(int version) throws InvalidDataException {
        switch (version) {
            case 0:
                this.version = MPEG_VERSION_2_5;
                break;
            case 2:
                this.version = MPEG_VERSION_2_0;
                break;
            case 3:
                this.version = MPEG_VERSION_1_0;
                break;
            default:
                throw new InvalidDataException("Invalid mpeg audio version in frame header");
        }
    }

    /**
     * Sets the layer from its header index.
     *
     * @param layer the layer index
     * @throws InvalidDataException if the index is reserved
     */
    private void setLayer(int layer) throws InvalidDataException {
        switch (layer) {
            case 1:
                this.layer = 3;
                break;
            case 2:
                this.layer = 2;
                break;
            case 3:
                this.layer = 1;
                break;
            default:
                throw new InvalidDataException("Invalid mpeg layer description in frame header");
        }
    }

    /**
     * Sets the CRC flag from its header bit.
     *
     * @param protectionBit the bit as read
     */
    private void setProtection(int protectionBit) {
        this.protection = (protectionBit == 1);
    }

    /**
     * Sets the bitrate from its header index.
     *
     * @param bitrate the bitrate index
     * @throws InvalidDataException if the index is invalid for the version and layer
     */
    private void setBitRate(int bitrate) throws InvalidDataException {
        if (MPEG_VERSION_1_0.equals(version)) {
            if (layer == 1) {
                switch (bitrate) {
                    case 1:
                        this.bitrate = 32;
                        return;
                    case 2:
                        this.bitrate = 64;
                        return;
                    case 3:
                        this.bitrate = 96;
                        return;
                    case 4:
                        this.bitrate = 128;
                        return;
                    case 5:
                        this.bitrate = 160;
                        return;
                    case 6:
                        this.bitrate = 192;
                        return;
                    case 7:
                        this.bitrate = 224;
                        return;
                    case 8:
                        this.bitrate = 256;
                        return;
                    case 9:
                        this.bitrate = 288;
                        return;
                    case 10:
                        this.bitrate = 320;
                        return;
                    case 11:
                        this.bitrate = 352;
                        return;
                    case 12:
                        this.bitrate = 384;
                        return;
                    case 13:
                        this.bitrate = 416;
                        return;
                    case 14:
                        this.bitrate = 448;
                        return;
                }
            } else if (layer == 2) {
                switch (bitrate) {
                    case 1:
                        this.bitrate = 32;
                        return;
                    case 2:
                        this.bitrate = 48;
                        return;
                    case 3:
                        this.bitrate = 56;
                        return;
                    case 4:
                        this.bitrate = 64;
                        return;
                    case 5:
                        this.bitrate = 80;
                        return;
                    case 6:
                        this.bitrate = 96;
                        return;
                    case 7:
                        this.bitrate = 112;
                        return;
                    case 8:
                        this.bitrate = 128;
                        return;
                    case 9:
                        this.bitrate = 160;
                        return;
                    case 10:
                        this.bitrate = 192;
                        return;
                    case 11:
                        this.bitrate = 224;
                        return;
                    case 12:
                        this.bitrate = 256;
                        return;
                    case 13:
                        this.bitrate = 320;
                        return;
                    case 14:
                        this.bitrate = 384;
                        return;
                }
            } else if (layer == 3) {
                switch (bitrate) {
                    case 1:
                        this.bitrate = 32;
                        return;
                    case 2:
                        this.bitrate = 40;
                        return;
                    case 3:
                        this.bitrate = 48;
                        return;
                    case 4:
                        this.bitrate = 56;
                        return;
                    case 5:
                        this.bitrate = 64;
                        return;
                    case 6:
                        this.bitrate = 80;
                        return;
                    case 7:
                        this.bitrate = 96;
                        return;
                    case 8:
                        this.bitrate = 112;
                        return;
                    case 9:
                        this.bitrate = 128;
                        return;
                    case 10:
                        this.bitrate = 160;
                        return;
                    case 11:
                        this.bitrate = 192;
                        return;
                    case 12:
                        this.bitrate = 224;
                        return;
                    case 13:
                        this.bitrate = 256;
                        return;
                    case 14:
                        this.bitrate = 320;
                        return;
                }
            }
        } else if (MPEG_VERSION_2_0.equals(version) || MPEG_VERSION_2_5.equals(version)) {
            if (layer == 1) {
                switch (bitrate) {
                    case 1:
                        this.bitrate = 32;
                        return;
                    case 2:
                        this.bitrate = 48;
                        return;
                    case 3:
                        this.bitrate = 56;
                        return;
                    case 4:
                        this.bitrate = 64;
                        return;
                    case 5:
                        this.bitrate = 80;
                        return;
                    case 6:
                        this.bitrate = 96;
                        return;
                    case 7:
                        this.bitrate = 112;
                        return;
                    case 8:
                        this.bitrate = 128;
                        return;
                    case 9:
                        this.bitrate = 144;
                        return;
                    case 10:
                        this.bitrate = 160;
                        return;
                    case 11:
                        this.bitrate = 176;
                        return;
                    case 12:
                        this.bitrate = 192;
                        return;
                    case 13:
                        this.bitrate = 224;
                        return;
                    case 14:
                        this.bitrate = 256;
                        return;
                }
            } else if (layer == 2 || layer == 3) {
                switch (bitrate) {
                    case 1:
                        this.bitrate = 8;
                        return;
                    case 2:
                        this.bitrate = 16;
                        return;
                    case 3:
                        this.bitrate = 24;
                        return;
                    case 4:
                        this.bitrate = 32;
                        return;
                    case 5:
                        this.bitrate = 40;
                        return;
                    case 6:
                        this.bitrate = 48;
                        return;
                    case 7:
                        this.bitrate = 56;
                        return;
                    case 8:
                        this.bitrate = 64;
                        return;
                    case 9:
                        this.bitrate = 80;
                        return;
                    case 10:
                        this.bitrate = 96;
                        return;
                    case 11:
                        this.bitrate = 112;
                        return;
                    case 12:
                        this.bitrate = 128;
                        return;
                    case 13:
                        this.bitrate = 144;
                        return;
                    case 14:
                        this.bitrate = 160;
                        return;
                }
            }
        }
        throw new InvalidDataException("Invalid bitrate in frame header");
    }

    /**
     * Sets the sample rate from its header index.
     *
     * @param sampleRate the sample rate index
     * @throws InvalidDataException if the index is reserved
     */
    private void setSampleRate(int sampleRate) throws InvalidDataException {
        if (MPEG_VERSION_1_0.equals(version)) {
            switch (sampleRate) {
                case 0:
                    this.sampleRate = 44100;
                    return;
                case 1:
                    this.sampleRate = 48000;
                    return;
                case 2:
                    this.sampleRate = 32000;
                    return;
            }
        } else if (MPEG_VERSION_2_0.equals(version)) {
            switch (sampleRate) {
                case 0:
                    this.sampleRate = 22050;
                    return;
                case 1:
                    this.sampleRate = 24000;
                    return;
                case 2:
                    this.sampleRate = 16000;
                    return;
            }
        } else if (MPEG_VERSION_2_5.equals(version)) {
            switch (sampleRate) {
                case 0:
                    this.sampleRate = 11025;
                    return;
                case 1:
                    this.sampleRate = 12000;
                    return;
                case 2:
                    this.sampleRate = 8000;
                    return;
            }
        }
        throw new InvalidDataException("Invalid sample rate in frame header");
    }

    /**
     * Sets the padding flag from its header bit.
     *
     * @param paddingBit the bit as read
     */
    private void setPadding(int paddingBit) {
        this.padding = (paddingBit == 1);
    }

    /**
     * Sets the private bit from its header bit.
     *
     * @param privateBit the bit as read
     */
    private void setPrivate(int privateBit) {
        this.privat = (privateBit == 1);
    }

    /**
     * Sets the channel mode from its header index.
     *
     * @param channelMode the mode index
     * @throws InvalidDataException if the index is reserved
     */
    private void setChannelMode(int channelMode) throws InvalidDataException {
        switch (channelMode) {
            case 0:
                this.channelMode = CHANNEL_MODE_STEREO;
                break;
            case 1:
                this.channelMode = CHANNEL_MODE_JOINT_STEREO;
                break;
            case 2:
                this.channelMode = CHANNEL_MODE_DUAL_MONO;
                break;
            case 3:
                this.channelMode = CHANNEL_MODE_MONO;
                break;
            default:
                throw new InvalidDataException("Invalid channel mode in frame header");
        }
    }

    /**
     * Sets the mode extension from its header index.
     *
     * @param modeExtension the extension index
     * @throws InvalidDataException if the index is invalid for the channel mode
     */
    private void setModeExtension(int modeExtension) throws InvalidDataException {
        if (!CHANNEL_MODE_JOINT_STEREO.equals(channelMode)) {
            this.modeExtension = MODE_EXTENSION_NA;
        } else {
            if (layer == 1 || layer == 2) {
                switch (modeExtension) {
                    case 0:
                        this.modeExtension = MODE_EXTENSION_BANDS_4_31;
                        return;
                    case 1:
                        this.modeExtension = MODE_EXTENSION_BANDS_8_31;
                        return;
                    case 2:
                        this.modeExtension = MODE_EXTENSION_BANDS_12_31;
                        return;
                    case 3:
                        this.modeExtension = MODE_EXTENSION_BANDS_16_31;
                        return;
                }
            } else if (layer == 3) {
                switch (modeExtension) {
                    case 0:
                        this.modeExtension = MODE_EXTENSION_NONE;
                        return;
                    case 1:
                        this.modeExtension = MODE_EXTENSION_INTENSITY_STEREO;
                        return;
                    case 2:
                        this.modeExtension = MODE_EXTENSION_M_S_STEREO;
                        return;
                    case 3:
                        this.modeExtension = MODE_EXTENSION_INTENSITY_M_S_STEREO;
                        return;
                }
            }
            throw new InvalidDataException("Invalid mode extension in frame header");
        }
    }

    /**
     * Sets the copyright flag from its header bit.
     *
     * @param copyrightBit the bit as read
     */
    private void setCopyright(int copyrightBit) {
        this.copyright = (copyrightBit == 1);
    }

    /**
     * Sets the original-media flag from its header bit.
     *
     * @param originalBit the bit as read
     */
    private void setOriginal(int originalBit) {
        this.original = (originalBit == 1);
    }

    /**
     * Sets the emphasis from the header bits.
     *
     * @param emphasis the emphasis bits read from the header
     * @throws InvalidDataException if the bits are not a valid emphasis
     */
    private void setEmphasis(int emphasis) throws InvalidDataException {
        switch (emphasis) {
            case 0:
                this.emphasis = EMPHASIS_NONE;
                break;
            case 1:
                this.emphasis = EMPHASIS__50_15_MS;
                break;
            case 3:
                this.emphasis = EMPHASIS_CCITT_J_17;
                break;
            default:
                throw new InvalidDataException("Invalid emphasis in frame header");
        }
    }

    /**
     * Returns the bitrate of the frame.
     *
     * @return the bitrate, in kbps
     */
    public int getBitrate() {
        return bitrate;
    }

    /**
     * Returns the channel mode of the frame.
     *
     * @return the mode name
     */
    public String getChannelMode() {
        return channelMode;
    }

    /**
     * Returns whether the copyright bit is set.
     *
     * @return true if the audio is copyrighted
     */
    public boolean isCopyright() {
        return copyright;
    }

    /**
     * Returns the emphasis of the frame.
     *
     * @return the emphasis name
     */
    public String getEmphasis() {
        return emphasis;
    }

    /**
     * Returns the MPEG layer of the frame.
     *
     * @return the layer name
     */
    public String getLayer() {
        return MPEG_LAYERS[layer];
    }

    /**
     * Returns the mode extension of the frame.
     *
     * @return the mode extension name
     */
    public String getModeExtension() {
        return modeExtension;
    }

    /**
     * Returns whether the original bit is set.
     *
     * @return true if the frame is original
     */
    public boolean isOriginal() {
        return original;
    }

    /**
     * Returns whether the padding bit is set.
     *
     * @return true if the frame has padding
     */
    public boolean hasPadding() {
        return padding;
    }

    /**
     * Returns whether the private bit is set.
     *
     * @return true if the private bit is set
     */
    public boolean isPrivate() {
        return privat;
    }

    /**
     * Returns whether the frame is protected by a CRC.
     *
     * @return true if a CRC follows the header
     */
    public boolean isProtection() {
        return protection;
    }

    /**
     * Returns the sample rate of the frame.
     *
     * @return the sample rate, in Hz
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the MPEG version of the frame.
     *
     * @return the version name
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the total length of the frame.
     *
     * @return the length, in bytes
     */
    public int getLengthInBytes() {
        long length;
        int pad;
        if (padding) pad = 1;
        else pad = 0;
        if (layer == 1) {
            length = ((48000 * bitrate) / sampleRate) + (pad * 4);
        } else {
            length = ((144000 * bitrate) / sampleRate) + pad;
        }
        return (int) length;
    }
}
