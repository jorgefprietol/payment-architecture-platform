FROM mcr.microsoft.com/dotnet/sdk:10.0@sha256:e70cdb7f80b0348f5cb85f19a8f670fca061f033d57eed12fa003d58b0e06317 AS build
WORKDIR /src
COPY global.json ./
COPY csharp/PaymentPlatform.csproj csharp/
RUN dotnet restore csharp/PaymentPlatform.csproj
COPY csharp/ csharp/
RUN dotnet publish csharp/PaymentPlatform.csproj -c Release --no-restore -o /out /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0@sha256:222759b391a1aaf241166672c8f99b2d4ada452e7b5319f3c6e8f265a37b5ad4
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/payment-architecture-platform" \
      org.opencontainers.image.title="Payment Platform CSharp" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/ ./
RUN mkdir /data && chown 10001:10001 /data
ENV ASPNETCORE_HTTP_PORTS=8080 DOTNET_EnableDiagnostics=0 SAGA_DATA_DIR=/data
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 CMD ["/bin/bash", "-c", "set -e; exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; read -r status <&3; [[ $status == *' 200 '* ]]"]
ENTRYPOINT ["dotnet", "/app/PaymentPlatform.dll"]
CMD ["--serve"]
