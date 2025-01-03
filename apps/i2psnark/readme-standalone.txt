I2PSNARK STANDALONE VERSION
===========================

To run I2PSnark's in standalone mode, make sure you have an I2P router running
in the background (I2P, I2P+ or i2pd work fine), then run:

* Linux/macOS etc: launch-i2psnark
* Windows: launch-i2psnark.bat

I2PSnark will be available at: http://127.0.0.1:8002/i2psnark/

* To change or disable browser launch at startup, edit i2psnark-appctx.config.
* To change the port, edit jetty-i2psnark.xml.


ADDING RPC SUPPORT
==================

1) Stop i2psnark standalone if running.

2a) If you have the i2psnark-rpc plugin installed in your router already, copy the file:
    ~/.i2p/plugins/i2psnark-rpc/console/webapps/transmission.war to the webapps/ directory
    in your standalone install.

2b) If you do not have the i2psnark-rpc plugin installed, pull the i2p.plugins.i2psnark-rpc
    branch from git, build with 'ant war', and copy the file src/build/transmission.war.jar
    to the file webapps/transmission.war in your standalone install.

3) Start i2psnark standalone as usual. The transmission web interface will be at
   http://127.0.0.1:8002/transmission/web/ or if you have transmission-remote installed,
   test with 'transmission-remote 8002 -l'


LICENSE
=======
I2PSnark is GPL'ed software, based on Snark (http://www.klomp.org/)