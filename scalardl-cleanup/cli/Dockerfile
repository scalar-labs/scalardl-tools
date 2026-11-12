# You can see sha256 hash in the following page:
# - https://hub.docker.com/_/eclipse-temurin/tags
FROM eclipse-temurin:21.0.13_9-jre-resolute@sha256:f8622d0220c9e218d7a33f24a7b9e1ad6bc726085f8b0ae74e1adf85f67f05c4 AS builder

COPY scalardl-cleanup.tar docker-entrypoint.sh /tmp/build/
RUN mkdir -p /out \
 && tar -xf /tmp/build/scalardl-cleanup.tar -C /out --strip-components=1 \
 && cp /tmp/build/docker-entrypoint.sh /out/ \
 && chmod +x /out/docker-entrypoint.sh

# You can see sha256 hash in the following page:
# - https://hub.docker.com/_/eclipse-temurin/tags
FROM eclipse-temurin:21.0.13_9-jre-resolute@sha256:f8622d0220c9e218d7a33f24a7b9e1ad6bc726085f8b0ae74e1adf85f67f05c4

RUN export DEBIAN_FRONTEND=noninteractive \
 && apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r --gid 201 scalar \
 && useradd -r --uid 201 -g scalar -s /usr/sbin/nologin scalar

WORKDIR /scalar

# Deliberately left owned by root:root (COPY's default — no --chown): uid 201 gets
# read/execute but cannot write, so a compromised JVM process cannot rewrite the
# entrypoint or the jars it is about to load.
COPY --from=builder /out /scalar/scalardl-cleanup

USER 201:201

ENV JAVA_OPT_MAX_RAM_PERCENTAGE='60.0'

ARG IMAGE_NAME
ARG IMAGE_VERSION
ARG IMAGE_RELEASE

LABEL name="${IMAGE_NAME}" \
      maintainer="Scalar, Inc." \
      vendor="Scalar, Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_RELEASE}" \
      summary="ScalarDL Cleanup Tools Container Image" \
      description="Command-line tools that let ScalarDL operators safely reclaim space by removing stale records left behind by completed transactions from Ledger's coordinator table and the Auditor's request proof table."

WORKDIR /scalar/scalardl-cleanup

ENTRYPOINT ["./docker-entrypoint.sh"]

CMD ["--help"]
