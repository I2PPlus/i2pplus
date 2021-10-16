package dummy;

/**
 *  Just more strings for xgettext, that don't appear in the source anywhere.
 *  I'm sure there's easier ways to do this, but this will do for now.
 *
 *  Obviously, do not compile this.
 */
class Dummy {
    void dummy {
        // wars for ConfigClientsHelper
        _t("addressbook");
        _t("i2psnark");
        _t("i2ptunnel");
        _t("susimail");
        _t("I2PMail");
        _t("susidns");
        _t("routerconsole");

        // clients, taken from clients.config, for ConfigClientsHelper
        // note that if the wording changes in clients.config, we have to
        // keep the old string here as well for existing installs
        _t("Web console");
        _t("SAM application bridge");
        _t("Application tunnels");
        _t("My eepsite web server");
        _t("I2P webserver (eepsite)");
        _t("I2P Web Server (eepsite)");
        _t("Browser launch at startup");
        _t("BOB application bridge");
        _t("I2P Router Console");
        _t("I2P+ Router Console");
        _t("Open Router Console in web browser at startup");

        // tunnel nicknames, taken from i2ptunnel.config so they will display
        // nicely under 'local destinations' in the summary bar
        // note that if the wording changes in i2ptunnel.config, we have to
        // keep the old string here as well for existing installs
        _t("shared clients");
        _t("Shared Clients");
        _t("I2PMail SMTP Server");
        _t("I2PMail POP3 Server");
        _t("Postman's SMTP Mail Server (smtp.postman.i2p)");
        _t("Postman's POP3 Mail Server (pop3.postman.i2p)");
        _t("I2P IRC Network");
        _t("Proxy to connect to I2P's anonymized IRC chat network");
        _t("I2P webserver");
        _t("I2P Webserver");
        _t("I2P Web Server");
        _t("Personal I2P Webserver (eepsite)");
        _t("HTTP Proxy");
        // older names for pre-0.7.4 installs
        // hardcoded in i2psnark
        _t("I2PSnark");
        // hardcoded in iMule?
        _t("iMule");
        _t("Official I2P Source Repository");

        // standard themes for ConfigUIHelper
        _t("dark");
        _t("light");
        _t("midnight");
        _t("vanilla");

        // stat groups for stats.jsp
        // See StatsGenerator for groups mapped to a display name
        _t("Bandwidth");
        _t("Encryption");
        _t("Peers");
        _t("Router");
        _t("Stream");
        _t("Transport");
        _t("Tunnels");

        // parameters in transport addresses (netdb.jsp)
        // may or may not be worth translating
        _t("host");
        _t("key");
        _t("port");
        // capabilities
        _t("caps");
    }
}
