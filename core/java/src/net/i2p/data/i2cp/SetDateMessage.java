package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.Clock;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;

/**
 * Tell the other side what time it is.
 * Only supported from router to client.
 *
 * Since 0.8.7, optionally include a version string.
 */
public class SetDateMessage extends I2CPMessageImpl {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 33;
    private Date _date;
    private String _version;

    /**
     * SetDateMessage.
     */
    public SetDateMessage() {
        super();
        _date = Date.from(Instant.ofEpochMilli(Clock.getInstance().now()));
    }

    /**
     * Router's version String to be sent to the client; may be null.
     *  @param version the router's version String to be sent to the client; may be null
     *  @since 0.8.7
     */
    public SetDateMessage(String version) {
        this();
        _version = version;
    }

    /**
     * Date.
     * @return the date
     */
    public Date getDate() {
        return _date;
    }

    /**
     * Current router date.
     */
    public void setDate(Date date) {
        _date = date;
    }

    /**
     *  Gets the protocol version.
     *
     *  @return may be null
     *  @since 0.8.7
     */
    public String getVersion() {
        return _version;
    }

    /**
     * Read the message body from the input stream.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _date = DataHelper.readDate(in);
            if (size > DataHelper.DATE_LENGTH) {
                _version = DataHelper.readString(in);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_date == null) {
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        }
        ByteArrayStream os = new ByteArrayStream(8 + 1 + 6);
        try {
            DataHelper.writeDate(os, _date);
            if (_version != null) {
                DataHelper.writeString(os, _version);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    /**
     * Type.
     * @return the type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("SetDateMessage: ");
        buf.append(_date);
        buf.append(" [Version: ").append(_version).append("]");
        return buf.toString();
    }
}
