# Capacidades de ingeniería

| Capacidad | Evidencia en el proyecto |
| --- | --- |
| Diseño modular | Inventario de componentes, acoplamiento estático y dinámico, métricas Ca/Ce/A/I/D |
| Migración incremental | Puerto de cotización, enrutamiento estable, comparación de lecturas y rollback |
| Coordinación distribuida | Orquestación, coreografía, comandos idempotentes y estados de compensación |
| Consistencia | Inbox analítico, deduplicación por evento y operación, overflow sin actualización parcial |
| Resiliencia | Backoff, circuit breaker, prueba de recuperación y aislamiento de capacidad |
| Contratos | DTOs equivalentes, schema versionado y paridad C#/Java por HTTP |
| Observabilidad | Logs JSON correlacionados, endpoints de salud y contadores exportados |
| Seguridad | Credencial obligatoria, comparación constante, autorización por scope y rate limiting |
| Entrega de software | CI en Windows/Linux, contenedores, escaneo, SBOM, procedencia firmada y artefactos por commit |
| Operación local | Runner privado, promoción sin recompilar, readiness, smoke tests y rollback |

Las evidencias corresponden al código y a las ejecuciones registradas. Las capacidades no implican historial laboral en una empresa ni operación bancaria real.

El almacenamiento de la saga y de analítica se mantiene en memoria; los snapshots permiten reanudación cuando un adaptador conserva su estado. El servicio HTTP publicado ofrece cotizaciones. La integración con un broker, un proveedor de identidad y una base transaccional se describe en el roadmap.
