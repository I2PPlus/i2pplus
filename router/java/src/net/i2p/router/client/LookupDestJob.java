/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package net.i2p.router.client;

import java.util.Locale;

import net.i2p.crypto.Blinding;
import net.i2p.data.Base32;
import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Look up the lease of a hash, to convert it to a Destination for the client.
 * Or, since 0.9.11, lookup a host name in the naming service.
 */
class LookupDestJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final long _reqID;
    private final long _timeout;
    private final Hash _hash;
    private final String _name;
    private final SessionId _sessID;
    private final Hash _fromLocalDest;
    private final BlindData _blindData;

//    private static final long DEFAULT_TIMEOUT = 15*1000;
    private static final long DEFAULT_TIMEOUT = 5*1000;

    public LookupDestJob(RouterContext context, ClientConnectionRunner runner, Hash h, Hash fromLocalDest) {
        this(context, runner, -1, DEFAULT_TIMEOUT, null, h, null, fromLocalDest);
    }

    /**
     *  One of h or name non-null.
     *
     *  For hash or b32 name, the dest will be returned if the LS can be found,
     *  even if the dest uses unsupported crypto.
     *
     *  @param reqID must be &gt;= 0 if name != null
     *  @param sessID must non-null if reqID &gt;= 0
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.11
     */
    public LookupDestJob(RouterContext context, ClientConnectionRunner runner,
                         long reqID, long timeout, SessionId sessID, Hash h, String name,
                         Hash fromLocalDest) {
        super(context);
        _log = context.logManager().getLog(LookupDestJob.class);
        if ((h == null && name == null) ||
            (h != null && name != null) ||
            (reqID >= 0 && sessID == null) ||
            (reqID < 0 && name != null)) {
            _log.warn("bad args");
            throw new IllegalArgumentException();
        }
        _runner = runner;
        _reqID = reqID;
        _timeout = timeout;
        _sessID = sessID;
        _fromLocalDest = fromLocalDest;
        BlindData bd = null;
        if (name != null && name.length() >= 60) {
            // convert a b32 lookup to a hash lookup
            String nlc = name.toLowerCase(Locale.US);
            if (nlc.endsWith(".b32.i2p")) {
                byte[] b = Base32.decode(nlc.substring(0, nlc.length() - 8));
                if (b != null) {
                    if (b.length == Hash.HASH_LENGTH) {
                        h = Hash.create(b);
                        if (_log.shouldDebug())
                            _log.debug("Converting name lookup " + name + " to " + h);
                        name = null;
                    } else if (b.length >= 35) {
                        // encrypted LS2
                        // lookup the blinded hash
                        try {
                            bd = Blinding.decode(context, b);
                            SigningPublicKey spk = bd.getUnblindedPubKey();
                            BlindData bd2 = _runner.getFloodfillNetworkDatabaseFacade().getBlindData(spk);
                            if (bd2 != null) {
                                // BlindData from database may have privkey or secret
                                // check if we need it but don't have it
                                if ((bd.getAuthRequired() && bd2.getAuthPrivKey() == null) ||
                                    (bd.getSecretRequired() && (bd2.getSecret() == null || bd2.getSecret().length() == 0))) {
                                    // don't copy over existing info, this will force an immediate
                                    // failure in runJob()
                                    if (_log.shouldDebug())
                                        _log.debug("No auth or secret, immediate fail " + bd);
                                } else {
                                    bd = bd2;
                                }
                            } else {
                                long now = getContext().clock().now();
                                bd.setDate(now);
                                long exp = now + ((bd.getAuthRequired() || bd.getSecretRequired()) ? 365*24*60*60*1000L
                                                                                                   :  90*24*68*60*1000L);
                                bd.setExpiration(exp);
                                _runner.getFloodfillNetworkDatabaseFacade().setBlindData(bd);
                            }
                            h = bd.getBlindedHash();
                            if (_log.shouldDebug())
                                _log.debug("Converting name lookup " + name + " to blinded " + h +
                                           " using BlindData:\n" + bd);
                            name = null;
                        } catch (RuntimeException re) {
                            if (_log.shouldWarn())
                                _log.debug("Failed blinding conversion of " + name, re);
                            // Do NOT lookup as a name, naming service will call us again and infinite loop
                            name = null;
                            // h and name both null, runJob will fail immediately
                        }
                    }
                }
            }
        }
        _hash = h;
        _name = name;
        _blindData = bd;
    }

    public String getName() { return _name != null ?
                                     "Lookup Hostname for Client" :
                                     "Lookup LeaseSet for Client";
    }

    public void runJob() {
        if (_blindData != null) {
            boolean fail1 = _blindData.getAuthRequired() && _blindData.getAuthPrivKey() == null;
            boolean fail2 = _blindData.getSecretRequired() &&
                            (_blindData.getSecret() == null || _blindData.getSecret().length() == 0);
            if (fail1 || fail2) {
                int code;
                if (fail1 && fail2)
                    code = HostReplyMessage.RESULT_SECRET_AND_KEY_REQUIRED;
                else if (fail1)
                    code = HostReplyMessage.RESULT_KEY_REQUIRED;
                else
                    code = HostReplyMessage.RESULT_SECRET_REQUIRED;
                if (_log.shouldDebug())
                    _log.debug("Failed b33 lookup " + _blindData.getUnblindedPubKey() + " with code " + code);
                returnFail(code);
                return;
            }
            // do this after the fail checks above, because even if we
            // have the dest, it won't help get a LS.
            Destination d = _blindData.getDestination();
            if (d != null) {
                if (_log.shouldDebug())
                    _log.debug("Found cached b33 lookup " + _blindData.getUnblindedPubKey() + " to " + d);
                returnDest(d);
                return;
            }
        }
        if (_name != null) {
            // inline, ignore timeout
            Destination d = getContext().namingService().lookup(_name);
            if (d != null) {
                if (_log.shouldDebug())
                    _log.debug("Successful name lookup [Hostname: " + _name + "]" + d);
                returnDest(d);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Failed name lookup [Address: " + _name.replace("\n", "") + "]");
                returnFail();
            }
        } else if (_hash != null) {
            DoneJob done = new DoneJob(getContext());
            // shorten timeout so we can respond before the client side times out
            long timeout = _timeout;
            if (timeout > 1500)
                timeout -= 500;
            // TODO tell router this is an encrypted lookup, skip 38 or earlier ffs?
            _runner.getFloodfillNetworkDatabaseFacade().lookupDestination(_hash, done, timeout, _fromLocalDest);
        } else {
            // blinding decode fail
            returnFail(HostReplyMessage.RESULT_DECRYPTION_FAILURE);
        }
    }

    private String toBase32() {
        if (_fromLocalDest != null)
            return _fromLocalDest.toBase32();
        return null;
    }

    private class DoneJob extends JobImpl {
        public DoneJob(RouterContext enclosingContext) {
            super(enclosingContext);
        }
        public String getName() { return "Lookup LeaseSet &amp; Reply to Client"; }
        public void runJob() {
            Destination dest = _runner.getFloodfillNetworkDatabaseFacade().lookupDestinationLocally(_hash);
            if (dest == null && _blindData != null) {
                // TODO store and lookup original hash instead
                LeaseSet ls = _runner.getFloodfillNetworkDatabaseFacade().lookupLeaseSetLocally(_hash);
                if (ls != null && ls.getType() == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    // already decrypted
                    EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                    LeaseSet decls = encls.getDecryptedLeaseSet();
                    if (decls != null) {
                        dest = decls.getDestination();
                    }
                }
            }
            if (dest != null) {
                if (_log.shouldDebug())
                    _log.debug("Successful destination lookup " + dest);
                returnDest(dest);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Failed destination lookup [Hash: " + _hash + "]");
                returnFail();
            }
        }
    }

    private void returnDest(Destination d) {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(_sessID, d, _reqID);
        else
            msg = new DestReplyMessage(d);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }

    /**
     *  Return the request ID or failed hash so the client can correlate replies with requests
     *  @since 0.8.3
     */
    private void returnFail() {
        returnFail(HostReplyMessage.RESULT_FAILURE);
    }

    /**
     *  Return the request ID or failed hash so the client can correlate replies with requests
     *  @param code failure code, greater than zero, only used for HostReplyMessage
     *  @since 0.9.43
     */
    private void returnFail(int code) {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(_sessID, code, _reqID);
        else if (_hash != null)
            msg = new DestReplyMessage(_hash);
        else
            return; // shouldn't happen
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }
}
