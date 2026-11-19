package net.i2p.i2ptunnel.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Simple stream for checking and optionally removing RFC2616 chunked encoding to the output.
 * <p>
 * Malformed framing is rejected rather than skipped: a byte that cannot appear
 * where the parser stands ends the stream with an IOException, so a desynchronised
 * parser cannot forward the rest of the body as chunk data.
 *
 * @since 0.9.62
 */
public class DechunkedOutputStream extends LimitOutputStream {

    /**
     * Largest chunk size the parser accepts, so a hostile 8-digit hex length
     * cannot ask for a 2 GB body. The pre-shift guard in
     * {@link #nextChunkSize(int, int)} keeps the accumulated value inside this
     * bound and keeps the shift from overflowing.
     */
    private static final int MAX_CHUNK_SIZE = 0x8000000;

    /** Most trailer header lines accepted before the terminating blank line. */
    private static final int MAX_TRAILER_LINES = 128;

    /** Most bytes in one trailer header line. */
    private static final int MAX_TRAILER_LINE_LENGTH = 8192;

    /**
     * Most bytes in one chunk header: the size digits, the extension after the ';'
     * and the line ending. A sender that writes "5;" and then an endless run of
     * bytes never leaves the extension state, so the body never completes, the done
     * callback never fires, and the request above it waits for a body that cannot
     * end.
     */
    private static final int MAX_CHUNK_HEADER_LENGTH = 64 * 1024;

    /**
     * Most bytes in the whole trailer section, all its header lines together. The
     * per-line and per-line-count ceilings above leave no room for a run of bare CR
     * bytes, which the parser accepts where a line ending belongs and counts
     * against neither.
     */
    private static final int MAX_TRAILER_LENGTH = 64 * 1024;

    /** True to strip the chunk headers and footers. */
    private final boolean _strip;
    /** The current state of the chunk parser. */
    private State _state = State.LEN;
    /** Bytes left in the current chunk, or the current trailer line size. */
    private int _remaining;
    /** Whether the chunk size being read has any hex digit yet. */
    private boolean _sawSizeDigit;
    /** Bytes of the chunk header being read, size digits and extension together. */
    private int _chunkHeaderBytes;
    /** Bytes of the trailer section being read, all its lines together. */
    private int _trailerBytes;
    /** Trailer header lines read since the last chunk. */
    private int _trailerLines;

    /**
     * The states of the chunk parser.
     */
    private enum State {
        /** Reading the chunk length digits. */
        LEN,
        /** Reading the CR after the length. */
        CR,
        /** Reading the LF after the length. */
        LF,
        /** Reading the chunk data. */
        DATA,
        /** Reading the CR after the data. */
        DATACR,
        /** Reading the LF after the data. */
        DATALF,
        /** Reading the trailer headers. */
        TRAILER,
        /** The chunked data fully parsed. */
        DONE
    }

    /**
     * Creates a new dechunked output stream.
     *
     * @param raw the underlying output stream
     * @param callback callback invoked when chunking is complete
     * @param strip true to strip chunk headers/footers, false to pass them through
     */
    public DechunkedOutputStream(OutputStream raw, DoneCallback callback, boolean strip) {
        super(raw, callback);
        _strip = strip;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        if (len <= 0)
            return;

        for (int i = 0; i < len; i++) {
            // _state is what we are expecting next
            switch (_state) {
                // collect chunk len and possible ';' then wait for extension if any and CRLF
                case LEN: {
                    int c = buf[off + i] & 0xff;
                    countChunkHeaderByte();
                    if (c >= '0' && c <= '9') {
                        _sawSizeDigit = true;
                        _remaining = nextChunkSize(_remaining, c - '0');
                    } else if (c >= 'a' && c <= 'f') {
                        _sawSizeDigit = true;
                        _remaining = nextChunkSize(_remaining, 10 + c - 'a');
                    } else if (c >= 'A' && c <= 'F') {
                        _sawSizeDigit = true;
                        _remaining = nextChunkSize(_remaining, 10 + c - 'A');
                    } else if (c == ';') {
                        requireSizeDigit();
                        _state = State.CR;
                    } else if (c == '\r') {
                        requireSizeDigit();
                        _state = State.LF;
                    } else if (c == '\n') {
                        requireSizeDigit();
                        startChunk();
                    } else {
                        throw new IOException("Unexpected length char 0x" + Integer.toHexString(c));
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                // collect any chunk extension and CR then wait for LF
                case CR: {
                    int c = buf[off + i] & 0xff;
                    countChunkHeaderByte();
                    if (c == '\r') {
                        _state = State.LF;
                    } else if (c == '\n') {
                        startChunk();
                    } else {
                        // chunk extension between the ';' and the CR, bounded by
                        // the chunk-header ceiling
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                // collect LF then wait for DATA
                case LF: {
                    int c = buf[off + i] & 0xff;
                    countChunkHeaderByte();
                    if (c == '\n') {
                        startChunk();
                    } else {
                        throw new IOException("no LF after CR");
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                // collect DATA then wait for LEN
                case DATA: {
                    int towrite = Math.min(_remaining, len - i);
                    out.write(buf, off + i, towrite);
                    // loop will increment
                    i += towrite - 1;
                    _remaining -= towrite;
                    if (_remaining <= 0)
                        _state = State.DATACR;
                    break;
                }

                // after DATA, collect CR then wait for LF
                case DATACR: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\r')
                        _state = State.DATALF;
                    else if (c == '\n')
                        _state = State.LEN;
                    else
                        throw new IOException("Chunk terminator is not CRLF");
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                // after DATA, collect LF then wait for LEN
                case DATALF: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\n')
                        _state = State.LEN;
                    else
                        throw new IOException("Chunk terminator is not CRLF");
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                // swallow and discard the Trailer headers until we find a plain CRLF
                // we reuse _remaining here to count the size of the header
                case TRAILER: {
                    int c = buf[off + i] & 0xff;
                    countTrailerByte();
                    if (c == '\r') {
                        // Stay here. A bare CR is still a byte of the trailer, and
                        // the section ceiling above is what stops a sender that
                        // sends CR over and over.
                    } else if (c == '\n') {
                        if (_remaining <= 0) {
                            // that's it!
                            if (!_strip)
                                out.write(buf, off + i, 1);
                            _state = State.DONE;
                            setDone();
                            return;
                        } else {
                            // stay here
                            if (++_trailerLines > MAX_TRAILER_LINES)
                                throw new IOException("Too many trailer headers");
                            _remaining = 0;
                        }
                    } else {
                        if (++_remaining > MAX_TRAILER_LINE_LENGTH)
                            throw new IOException("Trailer header too long");
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                case DONE: {
                    throw new EOFException((len - i) + " extra bytes written after chunking done");
                }
            }
        }
    }

    /**
     * Moves to the state that follows a completed chunk-size line.
     */
    private void startChunk() {
        if (_remaining > 0) {
            _state = State.DATA;
        } else {
            _state = State.TRAILER;
            _trailerLines = 0;
            _trailerBytes = 0;
        }
        _sawSizeDigit = false;
        _chunkHeaderBytes = 0;
    }

    /**
     * Counts one byte of the chunk header being read - size digits, the extension
     * after the ';' and the line ending - and rejects a header over the ceiling.
     * This is what ends a chunk extension that never does.
     *
     * @throws IOException if the header is over {@link #MAX_CHUNK_HEADER_LENGTH}
     */
    private void countChunkHeaderByte() throws IOException {
        if (++_chunkHeaderBytes > MAX_CHUNK_HEADER_LENGTH)
            throw new IOException("Chunk header too long");
    }

    /**
     * Counts one byte of the trailer section being read, every header line in it
     * together, and rejects a section over the ceiling. The per-line and per-line
     * ceilings do not bound a run of bare CR bytes, which the parser accepts where a
     * line ending belongs.
     *
     * @throws IOException if the section is over {@link #MAX_TRAILER_LENGTH}
     */
    private void countTrailerByte() throws IOException {
        if (++_trailerBytes > MAX_TRAILER_LENGTH)
            throw new IOException("Trailer section too long");
    }

    /**
     * Rejects a chunk-size line with no hex digits, which the parser used to read
     * as the terminating zero-length chunk and therefore ended the body early.
     *
     * @throws IOException if no digit has been read for this chunk size
     */
    private void requireSizeDigit() throws IOException {
        if (!_sawSizeDigit)
            throw new IOException("Empty chunk size line");
    }

    /**
     * Adds one hex digit to the chunk size being read.
     *
     * @param current the digits read so far, never negative
     * @param digit the value of the next hex digit, 0-15
     * @return the new chunk size, at most {@link #MAX_CHUNK_SIZE}
     * @throws IOException if the size would exceed {@link #MAX_CHUNK_SIZE}
     * @since 0.9.72+
     */
    private static int nextChunkSize(int current, int digit) throws IOException {
        // Checked before the shift, so the shift itself cannot overflow and turn
        // an oversized length into a small one.
        if (current > (MAX_CHUNK_SIZE - 0xf) / 16)
            throw new IOException("Chunk length too big");
        return (current << 4) | digit;
    }

}
