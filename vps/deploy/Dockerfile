# syntax=docker/dockerfile:1
FROM golang:1.26.4-bookworm AS builder
WORKDIR /source/vps
COPY vps/go.mod vps/go.sum ./
RUN go mod download
COPY vps/ ./
ARG SOURCE_COMMIT=development
RUN MODELATLAS_BUILD_COMMIT="$SOURCE_COMMIT" sh deploy/build.sh /out/modelatlas-vps

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates rclone \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 modelatlas \
    && useradd --uid 10001 --gid 10001 --home-dir /data --no-create-home --shell /usr/sbin/nologin modelatlas \
    && install -d -o 10001 -g 10001 -m 0700 /data /data/tmp
COPY --from=builder /out/modelatlas-vps /usr/local/bin/modelatlas-vps
COPY --chmod=755 vps/deploy/container-entrypoint.sh /usr/local/bin/modelatlas-entrypoint
ARG SOURCE_COMMIT=development
LABEL org.opencontainers.image.source="https://github.com/Tsenjii/ModelAtlas-2" \
      org.opencontainers.image.revision="$SOURCE_COMMIT"
ENV HOME=/data TMPDIR=/data/tmp
USER 10001:10001
WORKDIR /data
EXPOSE 8787
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["/usr/local/bin/modelatlas-vps", "ops", "ready", "--env-file", "/data/state/config/modelatlas.env"]
ENTRYPOINT ["/usr/local/bin/modelatlas-entrypoint"]
CMD ["portable", "run", "--directory", "/data/state"]
