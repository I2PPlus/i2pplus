Update via the console using ./graceful_update, graceful_update is designed to be used when your current I2P+ instance is running and you want to update without manually doing it. ./graceful_update will also gracefully restart the I2P+ router for you. ./graceful_update can be ran without i2p connected also by following the commands listed below or using ```./graceful_update --help``` PS: ./graceful_update will use eepget, curl, wget if told so.

* Note: To not automaticlly restart the router once the download is done use the ```-nr``` option for no-reboot, to test the command line download before downloading using ```-n``` option for a dry run(fake run, shows the download commands that would be used)

## Stable Release

```./graceful_update --stable```

This will attempt to use eepget to download the latest stable version from http://skank.i2p and gracefully restart the router

```./graceful_update --stable --any```

This will attempt to download the stable version using any way it finds, this includes, eepget, curl, wget, with and without 127.0.0.1:4444 I2P proxy tunnel, clearnet(i2pplus.com, gitlab.com) again it will gracefully restart the router for you.


## Developmental Release

```./graceful_update --devel```

Same as above but instead of stable it downloads devel.

```./graceful_update --devel --clear```

Downloads i2pupdate.zip devel release from https://ip2pplus.com

## Help
```$ ./graceful_update --help
Usage: graceful_update [options]
Update I2P+ and then perform a graceful or hard restart

Note: Either --devel or --stable MUST be specified unless -u is specified
  --help or -h   This help.
  --stable       Update to the latest stable release and gracefully restart the router
  --devel        Update to the latest devel release and gracefully restart the router
  -q             quiet(this overrides verbose and debug arguments)
  -v             verbose(default)
  -d             debug output
  -nr            No graceful restart after download
  --restart      Perform a hard restart without waiting for participating tunnels to expire
  -n             DRY RUN
  -u             URL to download i2pupdate.zip from
  -e             Use eepget as the downloader (default)
  -c             Use curl as the downloader
  -w             Use wget as the downloader
  -p             Use a proxy ([http(s)://]<ip:port> depending on downloader)
  -r             Download retries (default: 3)
  -t             Download timeout (default: 120)
  --any          Use any and all methods to download i2pupdate.zip(may use clearnet and/or tor over outproxy)
  --i2p          Download from skank.i2p (default)
  --tor          Download from I2P+ .onion address
  --clear        Download from i2pplus.com
  --git          Download from GitLab
  --insecure     Allows for insecure downloading when using a custom url
  --pid          Specify PID file name

Defaults: use eepget to download ip2update.zip the stable release from skank.i2p, and gracefully restart the router.
Quick start: ./graceful_update --stable, this will download most stable version using eepget only.

Notes:
* please edit the file ./graceful_update for more advanced configurations.
* -e, -c and -w can be used together.
* --i2p, --tor, --clear, and --git can be used together
* -p proxy setting will strip http(s) or add http(s) depending on the downloaders chosen
```

#### If you have questions about graceful_update please log onto IRC2P default servers, join #saltR and talk to term99 or dr|z3d.
