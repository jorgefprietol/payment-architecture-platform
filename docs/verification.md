# Verificación y evidencia

## Aceptación del núcleo

La ejecución local del 2 de octubre de 2026 produjo 47 escenarios correctos por implementación, compilación C# sin advertencias y paridad entre lenguajes. Las imágenes contenedorizadas ejecutaron los mismos escenarios correctamente.

```text
RESULT tests=47 failures=0 bucket=2332
TRACE migration=18468eada60a59cd526a6acc67d696653675850120f27a88383ecc9bc40b0a94
```

```powershell
./verify.ps1
docker run --rm payment-platform-csharp:local --verify
docker run --rm payment-platform-java:local --verify
```

Las pruebas cubren rollback, afinidad, compensación pendiente, conflictos de idempotencia, snapshots inmutables y reanudación. También cubren backoff acotado, recuperación del circuito, liberación de capacidad, concurrencia de duplicados, overflow sin mutación parcial, moneda, métricas, scopes y cuota.

## Contratos HTTP y contenedores

`verify.ps1` también ejecuta la suite durable contra procesos HTTP reales: reinicio antes del envío, fallo abrupto después del efecto antes del ack, replay sin duplicados, compensación, checkpoint compatible entre lenguajes y dispatcher automático. La CI ejecuta esa suite tanto en Windows como en Linux. Los smoke tests durables de contenedores reinician ambos servicios y exigen que el volumen conserve los efectos y el snapshot.

`scripts/smoke.ps1` exige readiness, rechaza solicitudes sin credencial, verifica el contrato de cotización y su correlation ID, comprueba errores de entrada y métricas y compara las respuestas C#/Java. Los contenedores llevan healthchecks del proceso y se instalan con `compose up --wait`.

El workflow se validó con actionlint fijado por digest. Todos los scripts PowerShell pasaron validación sintáctica. Docker Compose valida la configuración antes del arranque. La red configurable usa un bloque explícito `/28`, evitando depender de los pools automáticos de Docker Desktop.

## Evidencia de entrega

El [historial de CI](https://github.com/jorgefprietol/payment-architecture-platform/actions/workflows/ci.yml) conserva el estado y los logs de cada commit. Sus artefactos incluyen resultados de aceptación, informes de seguridad, SBOMs SPDX y el bundle firmado de la entrega. El controlador privado conserva los logs de verificación, promoción y recuperación.

Las pruebas locales no sustituyen la evidencia de una ejecución remota. Un artefacto se considera instalable únicamente después de que la ejecución completa del CI de `main` termine correctamente y se verifique su procedencia.

## Alcance

El SQL y el JSON Schema son recursos de referencia. La suite de aceptación verifica DTOs; los smoke tests verifican cotización y saga por HTTP. No se ejecutan PostgreSQL, CDC, Kafka ni validación OIDC. El servicio utiliza una credencial local. La saga y los recibos del participante se conservan en volúmenes; la analítica en memoria no ofrece persistencia después de una caída del proceso.
