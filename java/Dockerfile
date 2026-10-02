FROM eclipse-temurin:22-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS build
WORKDIR /src
COPY java/src/ src/
RUN mkdir -p /out/classes && javac -encoding UTF-8 --release 21 -d /out/classes src/platform/*.java

FROM eclipse-temurin:24-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/payment-architecture-platform" \
      org.opencontainers.image.title="Payment Platform Java" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/classes/ classes/
RUN mkdir /data && chown 10001:10001 /data
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError" SAGA_DATA_DIR=/data
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 CMD ["/bin/bash", "-c", "set -e; exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; read -r status <&3; [[ $status == *' 200 '* ]]"]
ENTRYPOINT ["java", "-cp", "/app/classes", "platform.Main"]
CMD ["--serve"]
