package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.MetaLease;
import net.i2p.data.MetaLeaseSet;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.util.OrderedProperties;

import java.util.Properties;

/**
 * Handle I2CP RequestVariableLeaseSetMessage from the router by granting all leases,
 * retaining the individual expiration time for each lease.
 *
 * @since 0.9.7
 */
class RequestVariableLeaseSetMessageHandler extends RequestLeaseSetMessageHandler {

    /**
     * RequestVariableLeaseSetMessageHandler.
     */
    public RequestVariableLeaseSetMessageHandler(I2PAppContext context) {
        super(context, RequestVariableLeaseSetMessage.MESSAGE_TYPE);
    }

    /**
     * Handle an incoming I2CP message, tracking how many prompt re-runs a
     * transient signing failure has already scheduled for this request.
     *
     * @param message the request from the router
     * @param session the session the request is for
     * @param attempt number of transient sign retries already performed
     * @since 0.9.71+
     */
    @Override
    protected void handleMessage(I2CPMessage message, I2PSessionImpl session, int attempt) {
        if (_log.shouldDebug()) {
            _log.debug("Handling " + message);
        }
        RequestVariableLeaseSetMessage msg = (RequestVariableLeaseSetMessage) message;
        boolean isLS2 = requiresLS2(session);
        // SubSession options aren't updated via the gui, so use the primary options
        Properties opts;
        if (session instanceof SubSession)
            opts = ((SubSession) session).getPrimaryOptions();
        else
            opts = session.getOptions();
        LeaseSet leaseSet;
        if (isLS2) {
            LeaseSet2 ls2;
            if (_ls2Type == DatabaseEntry.KEY_TYPE_LS2) {
                ls2 = new LeaseSet2();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                ls2 = new EncryptedLeaseSet();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                ls2 = new MetaLeaseSet();
            } else {
                session.propagateError("Unsupported LS2 type", new Exception());
                session.destroySession();
                return;
            }
            if (Boolean.parseBoolean(opts.getProperty("i2cp.dontPublishLeaseSet"))) {
                ls2.setUnpublished();
            }

            // Service records, proposal 167
            Properties props = null;
            StringBuilder buf = new StringBuilder(32);
            for (int i = 0; i < 10; i++) {
                buf.setLength(0);
                buf.append("i2cp.leaseSetOption.").append(i);
                String v = opts.getProperty(buf.toString());
                if (v == null) {
                    break;
                }
                String[] vs = DataHelper.split(v, "=", 2);
                if (vs.length < 2) {
                    continue;
                }
                if (props == null) {
                    props = new OrderedProperties();
                }
                props.setProperty(vs[0], vs[1]);
            }
            if (props != null) {
                ls2.setOptions(props);
            }

            leaseSet = ls2;
        } else {
            leaseSet = new LeaseSet();
        }
        String validationError = validateEndpointCount(msg.getEndpoints());
        if (validationError != null) {
            session.propagateError(validationError,
                    new IllegalStateException(validationError));
            return;
        }
        long publishStamp = 0;
        if (isLS2) {
            // Compute the monotonic publish floor only after the request is
            // known valid; it is committed after a successful sign below so
            // an empty or rejected request never pushes lastLS2SignTime
            // ahead of the real clock. See
            // RequestLeaseSetMessageHandler.handleMessage().
            // ensure 1-second resolution timestamp is higher than last one
            long now = Math.max(_context.clock().now(), session.getLastLS2SignTime() + 1000);
            ((LeaseSet2) leaseSet).setPublished(now);
            publishStamp = now;
        }
        // Full Meta support TODO
        long published = isLS2 ? ((LeaseSet2) leaseSet).getPublished() : 0;
        long current = _context.clock().now();
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease;
            if (isLS2) {
                // convert Lease to Lease2
                Lease old = msg.getEndpoint(i);
                if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                    lease = new MetaLease();
                } else {
                    lease = new Lease2();
                    lease.setTunnelId(old.getTunnelId());
                }
                lease.setGateway(old.getGateway());
                lease.setEndDate(ensurePositiveLs2Expiry(old.getEndTime(), published, current));
            } else {
                lease = msg.getEndpoint(i);
            }
            leaseSet.addLease(lease);
        }
        boolean signed = signLeaseSet(leaseSet, isLS2, session, msg, attempt);
        if (signed && publishStamp > 0) {
            // Advance the monotonic floor only on a successful sign.
            session.setLastLS2SignTime(publishStamp);
        }
    }
}
