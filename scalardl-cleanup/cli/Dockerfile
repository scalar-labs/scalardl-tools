FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get upgrade -y --no-install-recommends \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r --gid 201 scalar \
 && useradd -r --uid 201 -g scalar scalar

WORKDIR /scalar

COPY scalardl-cleanup.tar docker-entrypoint.sh /tmp/build/
RUN mkdir -p /scalar/scalardl-cleanup \
 && tar -xf /tmp/build/scalardl-cleanup.tar -C /scalar/scalardl-cleanup --strip-components=1 \
 && cp /tmp/build/docker-entrypoint.sh /scalar/scalardl-cleanup/ \
 && chmod +x /scalar/scalardl-cleanup/docker-entrypoint.sh \
 && rm -rf /tmp/build \
 && chown -R 201:201 /scalar

USER 201

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
      description="Command-line tools that let ScalarDL operators safely reclaim space by removing stale records left behind by completed transactions from ScalarDB's coordinator.state table (Ledger) and the Auditor's asset_lock/request_proof tables."

WORKDIR /scalar/scalardl-cleanup

ENTRYPOINT ["./docker-entrypoint.sh"]

CMD ["--help"]
