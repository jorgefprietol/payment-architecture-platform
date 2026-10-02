# Saga persistente y recuperación

## Contrato HTTP

Todas las operaciones requieren la misma credencial Bearer que las cotizaciones. Los UUID de saga y evento deben ser válidos y distintos de cero. No se envía un body; los dos parámetros del evento forman un contrato pequeño y explícito.

| Método y ruta | Resultado |
| --- | --- |
| `POST /sagas/{id}/events?eventId={uuid}&fact=Start` | Crea la saga y guarda el comando de reserva |
| `GET /sagas/{id}` | Estado, número de eventos, claves pendientes/confirmadas y efectos persistentes |
| `POST /sagas/{id}/dispatch` | Envía los comandos pendientes; permite reconciliación manual |

Secuencia correcta: `Start` → `Reserved` → `Approved` → `Settled`. La compensación recibe `Rejected` o `SettlementFailed`, y finaliza con `Released`. Cada hecho nuevo utiliza un UUID de evento distinto. Mientras el comando anterior esté pendiente, un hecho nuevo devuelve `409 pending_commands`; el emisor puede repetirlo cuando el dispatcher confirme la entrega. Un replay idéntico devuelve el checkpoint sin mutarlo; un payload distinto para el mismo eventId devuelve `409 idempotency_conflict`. Un hecho fuera de secuencia devuelve 409; entradas inválidas, 400; saga desconocida, 404; credencial ausente, 401; fallo de almacenamiento, 503.

## Unidad atómica y recepción idempotente

Cada checkpoint UTF-8 versionado contiene el snapshot, los hechos vistos y el outbox. El escritor crea un archivo temporal en el mismo directorio, vacía sus buffers y reemplaza el checkpoint. El proceso adquiere un bloqueo exclusivo del directorio y serializa las operaciones. Un segundo escritor debe usar otro volumen. El formato es equivalente en ambos lenguajes y se prueba recuperar el checkpoint usando la implementación opuesta.

La escritura usa [Flush(true) de .NET](https://learn.microsoft.com/en-us/dotnet/api/system.io.filestream.flush?view=net-10.0) y `FileChannel.force(true)` antes de [ATOMIC_MOVE de Java](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardCopyOption.html). Java falla si el filesystem no admite el movimiento atómico; no degrada a una copia parcial. Los archivos temporales de un proceso interrumpido no son checkpoints y no se leen al recuperar.

El participante local registra un recibo durable por `Command.Id`; ese recibo es su efecto observable. El dispatcher guarda el recibo antes de confirmar el outbox. Un fallo entre esos pasos deja el comando pendiente y el efecto ya registrado. Al reiniciar, el participante reconoce la misma clave y devuelve su recibo; el dispatcher confirma sin crear otro efecto. La semántica es entrega con reintentos y receptor idempotente. Un participante remoto real necesita guardar su cambio de negocio y su clave de deduplicación en la misma transacción.

## Recuperación y operación

El dispatcher revisa pendientes cada segundo y se reanuda al arrancar. `SAGA_AUTODISPATCH=false` permite ejecutar la recuperación manualmente. `SAGA_DATA_DIR` selecciona el directorio; Compose usa `/data` y volúmenes separados para C# y Java. El cambio de imagen y el rollback conservan esos volúmenes. `docker compose down` conserva datos; eliminar los volúmenes los elimina también.

`scripts/verify-durable.ps1` ejecuta procesos reales en puertos loopback temporales. Los termina después del commit y fuerza otro fallo después del recibo y antes del ack con `SAGA_FAIL_AFTER_EFFECT=1` (salida 86), usado sólo por pruebas. Después reinicia y exige exactamente un efecto por clave. También prueba conflicto de replay, bloqueo de hechos prematuros, happy path, compensación, recuperación automática y portabilidad del checkpoint. `scripts/smoke-durable.ps1 -RestartContainers` verifica persistencia después de reiniciar los contenedores; el despliegue ejecuta el mismo contrato sin reiniciar los servicios.

## Límites de la implementación

El almacenamiento tiene un escritor por volumen, un participante local y una saga pequeña con transiciones acotadas. No ofrece coordinación entre varias réplicas ni persistencia transaccional entre procesos independientes. Se verifica recuperación tras terminación del proceso y recreación del contenedor; no se afirma tolerancia a corte eléctrico, pérdida del disco o semántica atómica en volúmenes de red. El directorio no recibe sincronización adicional de metadata después del rename. Los datos del participante son recibos de comandos, sin integración con cuentas bancarias reales. Para un ledger real se requiere una base transaccional, invariantes monetarios y auditoría del participante.
