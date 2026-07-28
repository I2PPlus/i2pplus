package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.Log;

/**
 * Wrap up an outbound I2NP message, along with the information associated with its
 * delivery and jobs to be fired off if particular events occur.
 *
 */
public class OutNetMessage implements CDPQEntry {
    private final RouterContext _context;
    private final RouterInfo _target;
    private final I2NPMessage _message;
    private final int _messageTypeId;
    /** cached message ID, for use after we discard the message */
    private final long _messageId;
    private final int _messageSize;
    private final int _priority;
    private final long _expiration;
    private Job _onSend;
    private Job _onFailedSend;
    private ReplyJob _onReply;
    private Job _onFailedReply;
    private MessageSelector _replySelector;
    private List<String> _failedTransports;
    private long _sendBegin;
    private long _created;
    private long _enqueueTime;
    private long _transportQueued;
    private long _seqNum;
    private final boolean _shouldTimestamp;
    /** for debugging, contains a mapping of even name to Long (e.g. "begin sending", "handleOutbound", etc) */
    private HashMap<String, Long> _timestamps;
    /**
     * contains a list of timestamp event names in the order they were fired
     * (some JVMs have less than 10ms resolution, so the Long above doesn't guarantee order)
     */
    private List<String> _timestampOrder;

    /**
     *  Priorities, higher is higher priority.
     *  Lowest priority.
     *
     *  @since 0.9.3
     */
    public static final int PRIORITY_LOWEST = 100;
    /** Medium priority */
    public static final int PRIORITY_MEDIUM = 400;
    /** Highest priority */
    public static final int PRIORITY_HIGHEST = 1000;
    /** Build reply priority */
    public static final int PRIORITY_BUILD_REPLY = 300;
    /** Exploratory priority */
    public static final int PRIORITY_EXPLORATORY = 455;
    /** Participating priority */
    public static final int PRIORITY_PARTICIPATING = 200;
    /** My build request priority */
    public static final int PRIORITY_MY_BUILD_REQUEST = 500;
    /** My data priority */
    public static final int PRIORITY_MY_DATA = PRIORITY_HIGHEST;
    /** My netdb lookup priority */
    public static final int PRIORITY_MY_NETDB_LOOKUP = PRIORITY_HIGHEST;
    /** My netdb store low priority */
    public static final int PRIORITY_MY_NETDB_STORE_LOW = 250;
    /** My netdb store priority */
    public static final int PRIORITY_MY_NETDB_STORE = PRIORITY_HIGHEST;
    /** Netdb explore priority */
    public static final int PRIORITY_NETDB_EXPLORE = PRIORITY_LOWEST;
    /** Netdb flood priority */
    public static final int PRIORITY_NETDB_FLOOD = PRIORITY_HIGHEST;
    /** Netdb harvest priority */
    public static final int PRIORITY_NETDB_HARVEST = PRIORITY_LOWEST;
    /** Netdb reply priority */
    public static final int PRIORITY_NETDB_REPLY = 300;
    /** His build request priority */
    public static final int PRIORITY_HIS_BUILD_REQUEST = 300;
    /** His netdb store priority */
    public static final int PRIORITY_HIS_NETDB_STORE = 200;

    /**
     *  Null msg and target, zero expiration (used in OutboundMessageRegistry only)
     *
     *  @param context the router context
     *  @since 0.9.9
     */
    public OutNetMessage(RouterContext context) {this(context, null, 0, -1, null);}

    /**
     *  Standard constructor
     *
     *  @param context the router context
     *  @param msg generally non-null
     *  @param expiration the expiration time
     *  @param priority the priority
     *  @param target generally non-null
     *  @since 0.9.9
     */
    public OutNetMessage(RouterContext context, I2NPMessage msg, long expiration, int priority, RouterInfo target) {
        _context = context;
        _message = msg;
        if (msg != null) {
            _messageTypeId = msg.getType();
            _messageId = msg.getUniqueId();
            _messageSize = _message.getMessageSize();
        } else {
            _messageTypeId = 0;
            _messageId = 0;
            _messageSize = 0;
        }
        _priority = priority;
        _expiration = expiration;
        _target = target;

        _created = context.clock().now();
        Log log = context.logManager().getLog(OutNetMessage.class);
        _shouldTimestamp = log.shouldInfo();
        if (_shouldTimestamp) {timestamp("Created");}
    }

    /**
     * Stamp the message's progress.
     * Only useful if log level is INFO or DEBUG
     *
     * @param eventName what occurred
     */
    public void timestamp(String eventName) {
        if (_shouldTimestamp) {
            // only timestamp if we are debugging
            long now = _context.clock().now();
            synchronized (this) {
                locked_initTimestamps();
                _timestamps.put(eventName, Long.valueOf(now));
                _timestampOrder.add(eventName);
            }
        }
    }

    private void locked_initTimestamps() {
        if (_timestamps == null) {
            _timestamps = new HashMap<>(8);
            _timestampOrder = new ArrayList<>(8);
        }
    }

    /**
     * Specifies the router to which the message should be delivered.
     * Generally non-null but may be null in special cases.
     *
     * @return the target router
     */
    public RouterInfo getTarget() {return _target;}

    /**
     * Specifies the message to be sent.
     * Generally non-null but may be null in special cases.
     *
     * @return the message
     */
    public I2NPMessage getMessage() {return _message;}

    /**
     *  For debugging only.
     *
     *  @return the simple class name
     */
    public String getMessageType() {
        return _message != null ? _message.getClass().getSimpleName() : "null";
    }

    /** @return the message type ID */
    public int getMessageTypeId() {return _messageTypeId;}
    /** @return the message ID */
    public long getMessageId() {return _messageId;}

    /**
     * How large the message is, including the full 16 byte header.
     * Transports with different header sizes should adjust.
     *
     * @return the message size
     */
    public int getMessageSize() {
        return _messageSize;
    }

    /**
     *  Copies the message data to outbuffer.
     *  Used only by VM Comm System.
     *
     *  @param outBuffer the buffer to copy to
     *  @return the length, or -1 if message is null
     */
    public int getMessageData(byte[] outBuffer) {
        if (_message == null) {
            return -1;
        } else {
            return _message.toByteArray(outBuffer);
        }
    }

    /**
     * Specify the priority of the message, where higher numbers are higher
     * priority.  Higher priority messages should be delivered before lower
     * priority ones, though some algorithm may be used to avoid starvation.
     *
     * @return the priority
     */
    public int getPriority() {return _priority;}

    /**
     * Specify the # ms since the epoch after which if the message has not been
     * sent the OnFailedSend job should be fired and the message should be
     * removed from the pool.  If the message has already been sent, this
     * expiration is ignored and the expiration from the ReplySelector is used.
     *
     * @return the expiration time
     */
    public long getExpiration() {return _expiration;}

    /**
     * After the message is successfully passed to the router specified, the
     * given job is enqueued.
     *
     * @return the onSend job
     */
    public Job getOnSendJob() {return _onSend;}
    /**
     * setOnSendJob.
     */
    public void setOnSendJob(Job job) {_onSend = job;}

    /**
     * If the router could not be reached or the expiration passed, this job
     * is enqueued.
     *
     * @return the onFailedSend job
     */
    public Job getOnFailedSendJob() {return _onFailedSend;}
    /**
     * setOnFailedSendJob.
     */
    public void setOnFailedSendJob(Job job) {_onFailedSend = job;}

    /**
     * If the MessageSelector detects a reply, this job is enqueued
     *
     * @return the onReply job
     */
    public ReplyJob getOnReplyJob() {return _onReply;}
    /**
     * setOnReplyJob.
     */
    public void setOnReplyJob(ReplyJob job) {_onReply = job;}

    /**
     * If the Message selector is specified but it doesn't find a reply before
     * its expiration passes, this job is enqueued.
     *
     * @return the onFailedReply job
     */
    public Job getOnFailedReplyJob() {return _onFailedReply;}
    /**
     * setOnFailedReplyJob.
     */
    public void setOnFailedReplyJob(Job job) {_onFailedReply = job;}

    /**
     * Defines a MessageSelector to find a reply to this message.
     *
     * @return the reply selector
     */
    public MessageSelector getReplySelector() {return _replySelector;}
    /**
     * setReplySelector.
     */
    public void setReplySelector(MessageSelector selector) {_replySelector = selector;}

    /**
     * As of 0.9.55, returns the previous number of failed transports.
     *
     * @param transportStyle the transport that failed
     * @return the number of previously failed transports
     */
    public synchronized int transportFailed(String transportStyle) {
        int rv;
        if (_failedTransports == null) {
            _failedTransports = new ArrayList<>(2);
            rv = 0;
        } else {rv = _failedTransports.size();}
        _failedTransports.add(transportStyle);
        return rv;
    }

    /**
     * @return the number of failed transports
     * @since 0.9.55
     */
    public synchronized int getFailedTransportCount() {
        return (_failedTransports == null ? 0 : _failedTransports.size());
    }

    /**
     * As of 0.9.55, changed from a Set to a List
     *
     * @return the list of failed transports
     */
    public synchronized List<String> getFailedTransports() {
        return (_failedTransports == null ? Collections.<String>emptyList() : _failedTransports);
    }

    /** @return when the sending process began */
    public long getSendBegin() {return _sendBegin;}

    /** begin the send */
    public void beginSend() {_sendBegin = _context.clock().now();}

    /** @return the creation time */
    public long getCreated() {return _created;}

    /** Reset creation time for dispatch age tracking on requeue */
    public void resetCreatedTime() {_created = _context.clock().now();}

    /** @return the remaining lifetime */
    public long getLifetime() {return _context.clock().now() - _created;}

    /** @return the send time */
    public long getSendTime() {return _context.clock().now() - _sendBegin;}

    /**
     *  For CDQ
     *
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) {_enqueueTime = now;}

    /**
     *  For CDQ
     *
     *  @since 0.9.3
     * @return the enqueue time
     */
    public long getEnqueueTime() {return _enqueueTime;}

    /**
     *  When the message entered the transport queue (set by transport send()).
     *  @param now the queue time
     */
    public void setTransportQueued(long now) {_transportQueued = now;}

    /** @return when the message was queued */
    public long getTransportQueued() {return _transportQueued;}

    /**
     *  For CDQ
     *
     *  @since 0.9.3
     */
    public void drop() {
        // This is essentially what TransportImpl.afterSend(this, false) does
        // but we don't have a ref to the Transport.
        // No requeue with other transport allowed.
        if (_onFailedSend != null) {_context.jobQueue().addJob(_onFailedSend);}
        if (_onFailedReply != null) {_context.jobQueue().addJob(_onFailedReply);}
        if (_replySelector != null) {_context.messageRegistry().unregisterPending(this);}
        discardData();
        // we want this stat to reflect the lag
        _context.statManager().addRateData("transport.sendProcessingTime", _context.clock().now() - _enqueueTime);
    }

    /**
     *  For CDPQ
     *
     *  @since 0.9.3
     */
    public void setSeqNum(long num) {_seqNum = num;}

    /**
     *  For CDPQ
     *
     *  @since 0.9.3
     * @return the seq num
     */
    public long getSeqNum() {return _seqNum;}

    /**
     * We've done what we need to do with the data from this message, though
     * we may keep the object around for a while to use its ID, jobs, etc.
     */
    public void discardData() { /* No-op - data is not held separately in this implementation */ }

    /**
     * toString.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("\n* Contents: OutNetMessage").append(" [Priority: ").append(_priority).append("] ");
        if (_message == null) {buf.append("*no message*");}
        else {
            buf.append("with ").append(_messageSize).append(" byte ");
            buf.append(getMessageType());
            buf.append(" [MsgID ").append(_messageId).append("]");
        }
        if (_failedTransports != null) {
            buf.append("\n* Delivery failure on transports ").append(_failedTransports);
        }
        if (_target == null) {buf.append(" (null target)");}
        if (_onReply != null) {buf.append(" with onReply ").append(_onReply);}
        if (_onSend != null) {buf.append("; with onSend ").append(_onSend);}
        if (_onFailedReply != null) {buf.append("; with onFailedReply ").append(_onFailedReply);}
        if (_onFailedSend != null) {buf.append("; with onFailedSend ").append(_onFailedSend);}
        buf.append("\n* Expires: ").append(new Date(_expiration));
        if (_timestamps != null && _timestampOrder != null) {renderTimestamps(buf);}
        return buf.toString();
    }

    /**
     *  Only useful if log level is INFO or DEBUG;
     *  locked_initTimestamps() must have been called previously
     */
    private void renderTimestamps(StringBuilder buf) {
        synchronized (this) {
            long lastWhen = -1;
            if (_timestampOrder.size() > 1) {buf.append("\nTimestamps: ");}
            else {buf.append("\n* Time: ");}
            for (int i = 0; i < _timestampOrder.size(); i++) {
                String name = _timestampOrder.get(i);
                Long when = _timestamps.get(name);
                if (_timestampOrder.size() > 1) {buf.append("\n* ");}
                buf.append(name);
                buf.append(": ").append(new Date(when.longValue()));
                long diff = when.longValue() - lastWhen;
                buf.append(" (");
                if ((lastWhen > 0) && (diff > 500)) {buf.append("**");}
                if (lastWhen > 0) {buf.append(diff);}
                else {buf.append(0);}
                buf.append ("ms ago)");
                lastWhen = when.longValue();
            }
        }
    }

}
