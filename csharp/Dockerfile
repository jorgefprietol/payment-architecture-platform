FROM mcr.microsoft.com/dotnet/sdk:10.0@sha256:83e0db97c45d2e39b80123fe42940a23c423405a17f80b608a4b8768033d6392 AS build
WORKDIR /src
COPY global.json ./
COPY csharp/PaymentPlatform.csproj csharp/
RUN dotnet restore csharp/PaymentPlatform.csproj
COPY csharp/ csharp/
RUN dotnet publish csharp/PaymentPlatform.csproj -c Release --no-restore -o /out /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0@sha256:57460add89e2b3dd1950c41d8b7dc96eeb7a24d13d98e3656ce9997a8b746bd6
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/payment-architecture-platform" \
      org.opencontainers.image.title="Payment Platform CSharp" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/ ./
ENV ASPNETCORE_HTTP_PORTS=8080 DOTNET_EnableDiagnostics=0
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 CMD ["dotnet", "/app/PaymentPlatform.dll", "--healthcheck"]
ENTRYPOINT ["dotnet", "/app/PaymentPlatform.dll"]
CMD ["--serve"]
