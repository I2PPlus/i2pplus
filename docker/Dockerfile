FROM alpine:latest AS builder

ENV APP_HOME="/i2p"

WORKDIR /tmp/build

RUN apk add --no-cache gettext tar bzip2 apache-ant openjdk21 git uglify-js

COPY . .

RUN echo "build.built-by=DockerUser" >> override.properties
RUN echo "javac.compilerargs=-Xlint:none" >> override.properties

RUN ant preppkg-linux-only -q
RUN rm -rf /tmp/build-i2p/pkg-temp/osid /tmp/build-i2p/pkg-temp/lib/wrapper /tmp/build-i2p/pkg-temp/lib/wrapper.*

FROM alpine:latest
ENV APP_HOME="/i2p"

RUN apk add --no-cache openjdk21-jre ttf-opensans
WORKDIR ${APP_HOME}
COPY --from=builder /tmp/build-i2p/pkg-temp .

# Install I2P+ by copying over installed files
COPY docker/rootfs/ /
RUN chmod a+rx /startapp.sh

# Create i2p user and group
RUN addgroup -g 1000 i2p && \
    adduser -D -u 1000 -G i2p -s /bin/sh i2p

# Set ownership for i2p user
RUN chown -R i2p:i2p ${APP_HOME}

# Configure I2P+ to use random port (0=random) - override with --build-arg or -e at runtime
# ensure this port is port-forwarded and unfirewalled as required,
# otherwise your router will run in firewalled mode
ARG EXTERNAL_PORT=0
ENV EXTERNAL_PORT=${EXTERNAL_PORT}
RUN echo "i2np.udp.internalPort=17616=$EXTERNAL_PORT" >> ${APP_HOME}/router.config
RUN echo "i2np.udp.port=$EXTERNAL_PORT" >> ${APP_HOME}/router.config

# Mount home and snark
VOLUME ["${APP_HOME}/.i2p"]
VOLUME ["/i2psnark"]

EXPOSE 7654 7656 7657 7658 4444 6668 7659 7660 7667 12345

# Switch to i2p user
USER i2p

# Healthcheck - verify router is responsive
HEALTHCHECK --interval=5m --timeout=30s --start-period=2m --retries=3 \
    CMD wget -q -O- http://localhost:7657/jsp/status.jsp || exit 1

# Resource limits
# Memory limit is set via JVM_XMX in startapp.sh (default 512m)
# Additional ulimits for file operations
SHELL ["/bin/sh", "-c"]
RUN ulimit -n 65536

# DC6: For read-only root filesystem, use at runtime:
# docker run --read-only --tmpfs /i2p/.i2p:rw --tmpfs /i2psnark:rw ...

# Metadata
LABEL \
 org.label-schema.name="i2p" \
 org.label-schema.description="Docker container for I2P+" \
 org.label-schema.version="1.0" \
 org.label-schema.vcs-url="https://github.com/I2PPlus/i2pplus" \
 org.label-schema.schema-version="1.0"

ENTRYPOINT ["/startapp.sh"]
