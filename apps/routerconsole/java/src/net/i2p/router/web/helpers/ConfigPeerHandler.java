package net.i2p.router.web.helpers;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.web.FormHandler;

/**
 * Handler for peer configuration.
 */
public class ConfigPeerHandler extends FormHandler {
    private String _peer;
    private String _speed;
    private String _capacity;

    @Override
    protected void processForm() {
        if ("Save Configuration".equals(_action)) {
            _context.router().saveConfig();
            addFormNotice("Settings saved - not really!!!!!");
        } else if (_action.equals(_t("Ban peer until restart"))) {
            Hash h = getHash();
            if (h != null) {
                _context.banlist().banlistRouterForever(h, " <b>➜</b> " + _t("Manually banned via {0}"), "<a href=\"configpeer\">configpeer</a>");
                _context.commSystem().forceDisconnect(h);
                addFormNotice(_t("Peer") + " " + _peer + " " + _t("banned until restart"), true);
                return;
            }
            addFormError(_t("Invalid peer"), true);
        } else if (_action.equals(_t("Unban peer"))) {
            Hash h = getHash();
            if (h != null) {
                if (_context.banlist().isBanlisted(h)) {
                    _context.banlist().unbanlistRouter(h);
                    addFormNotice(_t("Peer") + " " + _peer + " " + _t("unbanned"), true);
                } else
                    addFormNotice(_t("Peer") + " " + _peer + " " + _t("is not currently banned"), true);
                return;
            }
            addFormError(_t("Invalid peer"), true);
        } else if (_action.equals(_t("Adjust peer bonuses"))) {
            Hash h = getHash();
            if (h != null) {
                PeerProfile prof = _context.profileOrganizer().getProfile(h);
                if (prof != null) {
                    try {
                        prof.setSpeedBonus(Integer.parseInt(_speed));
                    } catch (NumberFormatException nfe) {
                        addFormError(_t("Bad speed value"), true);
                    }
                    try {
                        prof.setCapacityBonus(Integer.parseInt(_capacity));
                    } catch (NumberFormatException nfe) {
                        addFormError(_t("Bad capacity value"), true);
                    }
                    addFormNotice(_t("Bonuses adjusted for: ") + _peer, true);
                } else
                    addFormError(_t("No profile exists for: ") + _peer, true);
                return;
            }
            addFormError(_t("Invalid peer"), true);
        } else if (_action.startsWith("Check")) {
            addFormError(_t("Unsupported"), true);
        }
    }

    private Hash getHash() {
        if (_peer != null && _peer.length() == 44) {
            byte[] b = Base64.decode(_peer);
            if (b != null)
                return new Hash(b);
        }
        return null;
    }

    public void setPeer(String peer) { _peer = peer; }
    public void setSpeed(String bonus) { _speed = bonus; }
    public void setCapacity(String bonus) { _capacity = bonus; }
}
