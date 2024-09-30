# I2P+ in Docker

## Quick start
If you just want to give I2P+ a quick try or are using it on a home network, follow these steps:

1  Install git, docker-io and docker-compose via your package manager
2. Download the I2P+ git repository with the command: `git pull https://github.com/I2PPlus/i2pplus.git`
3. Rename `docker-compose.example.yml` to `docker-compose.example.yml` in the root directory of your local I2P+ git workspace
4. As root, cd to the i2pplus git workspace you just downloaded and then execute `docker-compose up --build`
5. Start a browser and go to `http://127.0.0.1:7667` and then hit the Wizard link to configure your router
6. To stop the router, hit Ctrl+C and then, optionally, `docker-compose down`
7. To remove all existing cache files and generated images, run `docker system prune -a -f`

## Running a container

### Environment Variables

### Memory usage
By the default the image limits the memory available to the Java heap to 512MB. You can override that with the `JVM_XMX` environment variable.

### Ports
There are several ports which are exposed by the image. You can choose which ones to publish depending on your specific needs.

| Port   | Interface     | Description       | TCP/UDP |
|--------|---------------|-------------------|---------|
| 4444   | 127.0.0.1     | HTTP Proxy        | TCP     |
| 6668   | 127.0.0.1     | IRC Proxy         | TCP     |
| 7654   | 127.0.0.1     | I2CP Protocol     | TCP     |
| 7656   | 127.0.0.1     | SAM Bridge TCP    | TCP     |
| 7657   | 127.0.0.1     | Web Console       | TCP     |
| 7667   | 127.0.0.1     | Web Console (SSL) | TCP     |
| 7658   | 127.0.0.1     | I2P Webserver     | TCP     |
| 7659   | 127.0.0.1     | SMTP Proxy        | TCP     |
| 7660   | 127.0.0.1     | POP Proxy         | TCP     |
| 7652   | LAN interface | UPnP              | TCP     |
| 7653   | LAN interface | UPnP              | UDP     |
| RANDOM | 0.0.0.0       | I2NP Protocol     | TCP+UDP |

### Networking
You probably want at least the Router Console (7657) and the HTTP Proxy (4444). If you want I2P+ to be able to receive incoming connections from the internet, and hence not think it's firewalled, publish the I2NP Protocol port (randomly assigned when the router image is first started). Your allocated port will be listed in your Router Web Console at http://127.0.0.1:7667/info - by default the UDP port is also allocated for TCP connections.

A best-practices guide for cloud deployments is beyond the scope of this document, but in general you should try to minimize the number of published ports, while exposing only the `I2NP` ports to the internet. That means that the services in the list above which are bound to `127.0.0.1` (which include the router console) will need to be accessed via other methods like SSH tunneling or be manually configured to bind to a different interface.