# Capacidades de ingeniería

| Capacidad | Evidencia en el proyecto |
| --- | --- |
| Diseño modular | Inventario de componentes, acoplamiento estático y dinámico, métricas Ca/Ce/A/I/D |
| Migración incremental | Puerto de cotización, enrutamiento estable, comparación de lecturas y rollback |
| Coordinación distribuida | Orquestación, coreografía, comandos idempotentes y estados de compensación |
| Recuperación persistente | Snapshot, inbox y outbox atómicos, recibos durables, reinicios reales y checkpoints compatibles C#/Java |
| Consistencia | Inbox analítico, deduplicación por evento y operación, overflow sin actualización parcial |
| Resiliencia | Backoff, circuit breaker, prueba de recuperación y aislamiento de capacidad |
| Contratos | DTOs equivalentes, schema versionado y paridad C#/Java por HTTP |
| Observabilidad | Logs JSON correlacionados, endpoints de salud y contadores exportados |
| Seguridad | Credencial obligatoria, comparación constante, autorización por scope y rate limiting |
| Entrega de software | CI en Windows/Linux, contenedores, escaneo, SBOM, procedencia firmada y artefactos por commit |
| Operación local | Runner privado, promoción sin recompilar, readiness, smoke tests y rollback |

Las evidencias corresponden al código y a las ejecuciones registradas.

La saga se conserva en volúmenes mediante checkpoints atómicos y recibos del participante local. El servicio HTTP publicado ofrece cotizaciones y comandos de saga. La analítica permanece en memoria; un broker, OIDC y alta disponibilidad con base transaccional siguen siendo extensiones.
