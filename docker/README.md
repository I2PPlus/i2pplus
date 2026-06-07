# I2P+ in Docker

## Quick start
If you just want to give I2P+ a quick try or are using it on a home network, follow these steps:

### Pull from GitHub Container Registry

```bash
docker pull ghcr.io/i2pplus/i2pplus:latest
docker run -d -p 7657:7657 -p 7667:7667 -p 4444:4444 ghcr.io/i2pplus/i2pplus:latest
```

Then open http://127.0.0.1:7657 in your browser. The web console binds to `0.0.0.0` (all interfaces) inside the container, so you can also access it at your Docker host's IP address. The I2NP external port is assigned randomly - check your router console (Network section) or router.config for the assigned port. For full network participation, you'll need to map this port externally.

### Or build locally

1. Install git, docker-io and docker-compose via your package manager
2. Download the I2P+ git repository with the command: `git clone https://github.com/I2PPlus/i2pplus.git`
3. Copy `docker/docker-compose.yml` to `docker-compose.yml` in the root directory of your local I2P+ git workspace
4. cd to the i2pplus git workspace and execute `docker-compose up --build`
5. Start a browser and go to `http://127.0.0.1:7657` and then hit the Wizard link to configure your router
6. To stop the router, hit Ctrl+C and then, optionally, `docker-compose down`
7. To remove all existing cache files and generated images, run `docker system prune -a -f`

## Running a container

### Memory usage
By default the image limits the memory available to the Java heap to 512MB. You can override this at runtime with the `-e JVM_XMX=1024m` flag, or by modifying the `JVM_XMX` environment variable in the `docker/rootfs/startapp.sh` file before building.

### Security
The container runs as a non-root user `i2p` (UID 1000) for security. If you need to exec into the container for debugging, note that you are running as user `i2p`.

### Healthcheck
The image includes a HEALTHCHECK that monitors the router every 5 minutes. You can verify container health with:
```bash
docker inspect --format='{{.State.Health.Status}}' i2pplus
```

### Read-only root filesystem
For enhanced security, you can run with a read-only root filesystem:
```bash
docker run --read-only --tmpfs /i2p/.i2p:rw --tmpfs /i2psnark:rw ...
```

### Ports
There are several ports which are exposed by the image. You can choose which ones to publish depending on your specific needs.

| Port     | Interface       | Description           | TCP/UDP   |
| -------- | --------------- | --------------------- | --------- |
| 4444     | 127.0.0.1       | HTTP Proxy            | TCP       |
| 6668     | 127.0.0.1       | IRC Proxy             | TCP       |
| 7654     | 127.0.0.1       | I2CP Protocol         | TCP       |
| 7656     | 127.0.0.1       | SAM Bridge TCP        | TCP       |
| 7657     | 0.0.0.0         | Web Console (non-SSL) | TCP       |
| 7667     | 0.0.0.0         | Web Console (SSL)     | TCP       |
| 7658     | 127.0.0.1       | I2P Webserver         | TCP       |
| 7659     | 127.0.0.1       | SMTP Proxy            | TCP       |
| 7660     | 127.0.0.1       | POP Proxy             | TCP       |
| 7652     | LAN interface   | UPnP                  | TCP       |
| 7653     | LAN interface   | UPnP                  | UDP       |
| RANDOM   | 0.0.0.0         | I2NP Protocol         | TCP+UDP   |

### Networking
At the minimum, you'll want the Router Console (7657) and the HTTP Proxy (4444) available on localhost or your LAN network. Most services bind to `127.0.0.1` and will only be available inside the container. The web console binds to `0.0.0.0` so it's accessible from your Docker host by default — both the non-SSL (7657) and SSL (7667) ports are bound. Services should not be exposed to the public internet. They can be disabled in the I2P+ web console if not required.

To receive inbound connections from peers, you must expose the external I2NP port (TCP+UDP). Without it, the router will show as firewalled and rely on hole punching, which is less reliable. See [External Network Port](#external-network-port) below for how to set a fixed port for port forwarding.

#### External Network Port
By default, I2P+ uses a random port for the I2NP Protocol (TCP+UDP). This is recommended for security - avoid using a fixed port as it's fingerprintable. The port is assigned at first start and remains consistent for that container (stored in router.config).

**Set at build time:**
```bash
docker build --build-arg EXTERNAL_PORT=12345 -t i2pplus .
```

**Or set at runtime:**
```bash
docker run -e EXTERNAL_PORT=12345 i2pplus:latest
```

Your allocated port will be listed in your Router Web Console at http://127.0.0.1:7657/info. Note: This is the *only* port that you need to expose to the public internet, access to other ports should only be permitted from localhost or your LAN.

## Useful Docker Commands

### Build the image
```bash
# From project root
docker build -t i2pplus:latest -f docker/Dockerfile .

# Save to file for transfer
docker save i2pplus:latest -o /tmp/i2pplus.tar

# Load from file
docker load -i /tmp/i2pplus.tar
```

### Run the container
```bash
# Basic run (with port mapping for console access)
docker run -d -p 7657:7657 -p 7667:7667 -p 4444:4444 --name i2pplus i2pplus:latest

# With persistent config (survives container restart)
# Note: The external I2NP port is random - see your router console or router.config for the assigned port
docker run -d -v /path/to/i2p-data:/i2p/.i2p \
           -v /path/to/snark:/i2psnark \
           -p 7657:7657 -p 7667:7667 -p 4444:4444 \
           --name i2pplus i2pplus:latest

# Override JVM heap size
docker run -d -e JVM_XMX=1024m i2pplus:latest

# Override JAVA options
docker run -d -e JAVA17OPTS="-XX:+UseG1GC" i2pplus:latest
```

### Manage the container
```bash
# View logs
docker logs i2pplus
docker logs -f i2pplus  # follow

# Interactive shell (for debugging)
docker exec -it i2pplus /bin/bash

# Stop/Start
docker stop i2pplus
docker start i2pplus

# Remove container
docker rm -f i2pplus
```

### docker-compose
```bash
# Start
docker-compose up --build -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

### Troubleshooting
```bash
# Check container status
docker ps -a

# Check resource usage
docker stats i2pplus

# Check configuration
docker exec i2pplus cat /i2p/router.config

# Shell script syntax check
bash -n docker/rootfs/startapp.sh && echo "OK"
```

### Cleanup

#### Remove all data and restart fresh
```bash
# Stop container (if running) and remove
docker stop i2pplus 2>/dev || true
docker rm -f i2pplus

# Remove volumes (config, plugins, reseed data)
docker volume rm i2pplus_i2p-home  # or your volume name
docker volume rm i2pplus_i2psnark  # or your volume name

# Or if using bind mounts, delete the host directories
rm -rf /path/to/i2p-data
rm -rf /path/to/snark

# Rebuild and start fresh
docker build -t i2pplus:latest -f docker/Dockerfile .
docker run -d -v /path/to/i2p-data:/i2p/.i2p -v /path/to/snark:/i2psnark i2pplus:latest
```

#### Clear specific caches (without losing config)
```bash
# Enter container
docker exec -it i2pplus /bin/bash

# Clear router cache
rm -rf /i2p/.i2p/routerCache

# Clear profile (WARNING: this deletes ALL data including config - only use if you want a fresh start)
rm -rf /i2p/.i2p/

# Clear I2PSnark torrents
rm -rf /i2psnark/*

# Exit container and restart
exit
docker restart i2pplus
```

#### Clean up Docker resources
```bash
# Use the cleanup script (recommended)
./docker/cleanup.sh --all

# Or manually:
# Remove stopped containers
docker container prune -f

# Remove unused images
docker image prune -a -f

# Remove unused volumes
docker volume prune -f

# Full cleanup
docker system prune -a -f
```

The `cleanup.sh` script provides an interactive way to clean up specific resources:
```bash
./docker/cleanup.sh --help
```

---