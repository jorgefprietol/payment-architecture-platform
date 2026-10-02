FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
WORKDIR /src
COPY java/src/ src/
RUN mkdir -p /out/classes && javac -encoding UTF-8 --release 21 -d /out/classes src/platform/*.java

FROM eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/payment-architecture-platform" \
      org.opencontainers.image.title="Payment Platform Java" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/classes/ classes/
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=10s --start-period=30s --retries=3 CMD ["java", "-cp", "/app/classes", "platform.Main", "--healthcheck"]
ENTRYPOINT ["java", "-cp", "/app/classes", "platform.Main"]
CMD ["--serve"]
