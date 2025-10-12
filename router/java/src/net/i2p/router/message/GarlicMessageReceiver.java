package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 * Handles the reception and decryption of GarlicMessages. Decrypts the garlic message using
 * the appropriate keys, validates each garlic clove, and passes valid cloves to the configured
 * {@link CloveReceiver} for dispatch.
 *
 * This class supports decryption keys of various types including ElGamal, ECIES, and PQ keys,
 * and performs key selection based on availability.
 *
 * The receiver can be configured to target a specific client destination, allowing session-key
 * and tunnel settings to be applied accordingly.
 */
public class GarlicMessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final CloveReceiver _receiver;
    private final Hash _clientDestination;

    /**
     * Interface for handling decrypted garlic cloves. Implementations should process or dispatch
     * the cloves as needed.
     */
    public interface CloveReceiver {
        /**
         * Handles a decrypted garlic clove.
         *
         * @param instructions Delivery instructions associated with the clove.
         * @param data         The I2NPMessage data carried by the clove.
         */
        void handleClove(DeliveryInstructions instructions, I2NPMessage data);
    }

    /**
     * Constructs a GarlicMessageReceiver without targeting a specific client destination.
     *
     * @param context  The router context containing necessary services.
     * @param receiver The non-null CloveReceiver to handle valid cloves.
     */
    public GarlicMessageReceiver(final RouterContext context, final CloveReceiver receiver) {
        this(context, receiver, null);
    }

    /**
     * Constructs a GarlicMessageReceiver targeting a specific client destination.
     *
     * @param context           The router context containing necessary services.
     * @param receiver          The non-null CloveReceiver to handle valid cloves.
     * @param clientDestination The client destination hash this receiver targets, or null to target self.
     */
    public GarlicMessageReceiver(final RouterContext context, final CloveReceiver receiver, final Hash clientDestination) {
        _context = context;
        _log = context.logManager().getLog(GarlicMessageReceiver.class);
        _clientDestination = clientDestination;
        _receiver = receiver;
    }

    /**
     * Processes a received GarlicMessage by attempting to decrypt it with the appropriate keys.
     * Valid cloves are passed to the configured CloveReceiver. If decryption fails, statistics and
     * message history are updated accordingly.
     *
     * @param message The incoming GarlicMessage to be processed.
     */
    public void receive(final GarlicMessage message) {
        PrivateKey decryptionKey;
        PrivateKey decryptionKey2 = null;
        SessionKeyManager skm;

        final boolean debug = _log.shouldDebug();

        Hash destinationHash = null;
        TunnelPoolSettings in = null;
        TunnelPoolSettings out = null;
        String nick = "";
        String name = "";

        if (_clientDestination != null) {
            destinationHash = _clientDestination.calculateHash();
            in = _context.tunnelManager().getInboundSettings(destinationHash);
            out = _context.tunnelManager().getOutboundSettings(destinationHash);

            // Prepare logging names if debugging
            if (debug) {
                if (in != null) {
                    name = in.getDestinationNickname();
                } else if (out != null) {
                    name = out.getDestinationNickname();
                }
                nick = (name.isEmpty()) ? "[" + _clientDestination.toBase32().substring(0, 8) + "]" : name;
            }
        }

        // Retrieve keys and session key manager for the destination or self
        if (_clientDestination != null) {
            LeaseSetKeys keys = _context.keyManager().getKeys(_clientDestination);
            skm = _context.clientManager().getClientSessionKeyManager(_clientDestination);

            if (keys == null || skm == null) {
                if (debug) {
                    _log.warn("Not decrypting " + message + " for disconnected client " + nick);
                }
                return;
            }

            decryptionKey = keys.getDecryptionKey();
            decryptionKey2 = keys.getDecryptionKey(EncType.ECIES_X25519);
            final PrivateKey decryptionKey3 = keys.getPQDecryptionKey();

            if (decryptionKey == null && decryptionKey2 == null && decryptionKey3 == null) {
                if (debug) {
                    _log.warn("No key to decrypt for client destination " + nick);
                }
                return;
            }

            // If ElGamal key is null, swap to PQ or ECIES keys if available
            if (decryptionKey == null) {
                if (decryptionKey3 != null) {
                    decryptionKey = decryptionKey3; // PQ priority
                } else {
                    // EC only
                    decryptionKey = decryptionKey2;
                    decryptionKey2 = null;
                }
            }
        } else {
            decryptionKey = _context.keyManager().getPrivateKey();
            skm = _context.sessionKeyManager();
        }

        // Attempt to decrypt using one or two keys
        final CloveSet set;
        if (decryptionKey2 != null) {
            set = _context.garlicMessageParser().getGarlicCloves(message, decryptionKey, decryptionKey2, skm);
        } else {
            set = _context.garlicMessageParser().getGarlicCloves(message, decryptionKey, skm);
        }

        if (set != null) {
            final int cloveCount = set.getCloveCount();
            for (int i = 0; i < cloveCount; i++) {
                final GarlicClove clove = set.getClove(i);
                handleClove(clove);
            }
        } else {
            if (debug) {
                final boolean isUs = _clientDestination == null || (_clientDestination != null &&
                        _context.routerHash().toBase32().startsWith(_clientDestination.toBase32().substring(0, 6)));
                final String d = (_clientDestination != null && !isUs) ? nick : "Our Router";
                final String keysUsed = (decryptionKey2 != null) ? "both ElGamal and ECIES keys" : decryptionKey.getType().toString();
                _log.warn("Failed to decrypt " + message + " with " + keysUsed + " -> Target: " + d);
            }
            _context.statManager().addRateData("crypto.garlic.decryptFail", 1);
            _context.messageHistory().messageProcessingError(message.getUniqueId(), message.getClass().getName(),
                    "Garlic could not be decrypted");
        }
    }

    /**
     * Validates a GarlicClove and, if valid, passes it to the configured receiver.
     *
     * @param clove The garlic clove to validate and handle.
     */
    private void handleClove(final GarlicClove clove) {
        if (!isValid(clove)) {
            return;
        }
        _receiver.handleClove(clove.getInstructions(), clove.getData());
    }

    /**
     * Checks if the given clove is valid according to expiration and message validation rules.
     * Logs debug or warning messages if invalid.
     *
     * @param clove The garlic clove to validate.
     * @return true if the clove is valid; false otherwise.
     */
    private boolean isValid(final GarlicClove clove) {
        /*
         * As of 0.9.44, the clove ID is not checked in the Bloom filter, only expiration is checked.
         * The Clove ID is just a random number and the message ID in the clove is checked in the Bloom filter,
         * which is considered sufficient.
         *
         * Checking the clove ID as well would double Bloom filter entries and increase false positives.
         *
         * For ECIES-Ratchet, the clove ID is set to the message ID after decryption since no separate field is transmitted.
         */

        final String invalidReason = _context.messageValidator().validateMessage(clove.getExpiration());
        final boolean valid = invalidReason == null;

        if (!valid) {
            final String howLongAgo = DataHelper.formatDuration(_context.clock().now() - clove.getExpiration());
            if (_log.shouldDebug()) {
                _log.debug("Clove [" + clove.getCloveId() + "] is NOT valid -> Expired " + howLongAgo + " ago",
                        new Exception("Invalid clove expiration"));
            } else if (_log.shouldWarn()) {
                _log.warn("Clove [" + clove.getCloveId() + "] is NOT valid -> Expired " + howLongAgo + " ago: "
                        + invalidReason + ": " + clove);
            }
            _context.messageHistory().messageProcessingError(clove.getCloveId(),
                    clove.getData().getClass().getSimpleName(),
                    "Clove is not valid (expired " + howLongAgo + " ago)");
        }

        return valid;
    }
}
