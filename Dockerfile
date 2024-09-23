FROM alpine:latest AS builder

ENV APP_HOME="/i2p"

WORKDIR /tmp/build
COPY . .

RUN apk add --virtual build-base gettext tar bzip2 apache-ant openjdk21 \
    && echo "build.built-by=Docker" >> override.properties \
    && ant preppkg-linux-only \
    && rm -rf pkg-temp/osid pkg-temp/lib/wrapper pkg-temp/lib/wrapper.* \
    && apk del build-base gettext tar bzip2 apache-ant openjdk21

FROM alpine:latest
ENV APP_HOME="/i2p"

RUN apk add openjdk21-jre ttf-opensans
WORKDIR ${APP_HOME}
COPY --from=builder /tmp/build/pkg-temp .

# "install" I2P+ by copying over installed files
COPY docker/rootfs/ /
RUN chmod +x /startapp.sh

# Mount home and snark
VOLUME ["${APP_HOME}/.i2p"]
VOLUME ["/i2psnark"]

EXPOSE 7654 7656 7657 7658 4444 6668 7659 7660 7667 12345

# Metadata.
LABEL \
      org.label-schema.name="i2p" \
      org.label-schema.description="Docker container for I2P+" \
      org.label-schema.version="1.0" \
      org.label-schema.vcs-url="https://gitlab.com/i2pplus/I2P.Plus" \
      org.label-schema.schema-version="1.0"

ENTRYPOINT ["/startapp.sh"]
