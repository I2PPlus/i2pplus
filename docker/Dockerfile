FROM alpine:latest AS builder

ENV APP_HOME="/i2p"

WORKDIR /tmp/build

RUN apk add gettext tar bzip2 apache-ant openjdk21 git uglify-js
RUN wget https://repo1.maven.org/maven2/ant-contrib/ant-contrib/1.0b3/ant-contrib-1.0b3.jar -O /usr/share/java/ant-contrib.jar

COPY . .

RUN echo "build.built-by=DockerUser" >> override.properties
RUN echo "javac.compilerargs=-Xlint:none" >> override.properties

RUN ant preppkg-linux-only -q
RUN rm -rf pkg-temp/osid pkg-temp/lib/wrapper pkg-temp/lib/wrapper.*

FROM alpine:latest
ENV APP_HOME="/i2p"

RUN apk add openjdk21-jre ttf-opensans
WORKDIR ${APP_HOME}
COPY --from=builder /tmp/build/pkg-temp .

# Install I2P+ by copying over installed files
COPY docker/rootfs/ /
RUN chmod +x /startapp.sh

# Configure I2P+ to use chosen port - ensure this port is port-forwarded and unfirewalled as required,
# otherwise your router will run in firewalled mode
RUN echo "i2np.udp.internalPort=17616=$EXTERNAL_PORT" >> ${APP_HOME}/router.config
RUN echo "i2np.udp.port=$EXTERNAL_PORT" >> ${APP_HOME}/router.config

# Mount home and snark
VOLUME ["${APP_HOME}/.i2p"]
VOLUME ["/i2psnark"]

EXPOSE 7654 7656 7657 7658 4444 6668 7659 7660 7667 12345

# Metadata
LABEL \
 org.label-schema.name="i2p" \
 org.label-schema.description="Docker container for I2P+" \
 org.label-schema.version="1.0" \
 org.label-schema.vcs-url="https://github.com/I2PPlus/i2pplus" \
 org.label-schema.schema-version="1.0"

ENTRYPOINT ["/startapp.sh"]
