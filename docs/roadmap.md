# Extensiones y criterios de aceptación

1. **Modularizar por necesidad.** Añadir un contexto de notificaciones al inventario. Debe conservar un grupo independiente cuando recibe hechos asíncronos y posee despliegue y datos propios. Explicar qué ocurre si comparte base con el ledger y qué evidencias faltan para determinar su quantum.

2. **Saga persistente implementada.** El adaptador guarda snapshot, inbox y outbox en un checkpoint atómico. Las pruebas fuerzan reinicios después del commit y después del efecto antes del ack, verifican deduplicación y recuperación C#/Java. La siguiente extensión es trasladar el mismo contrato a una base transaccional y participantes en procesos independientes.

3. **Resultado incierto.** Simular timeout después de una liquidación confirmada. Consultar el participante por Command.Id antes de compensar. Verificar que no se libera una reserva de una operación ya liquidada.

4. **Coreografía y causalidad.** Añadir inbox por participante y una cola de eventos fuera de orden. Repetir `Reserved` y `Approved`: no debe duplicarse ni la reserva ni la liquidación. Un proceso de reconciliación debe detectar sagas estancadas.

5. **Extraer datos.** Construir una matriz de tablas y propietario; mover analítica a su almacenamiento. El ledger no debe leer las tablas analíticas. El consumidor debe reconstruir su vista sin modificar el historial operacional.

6. **Migración completa.** Exponer el puerto de cotización mediante HTTP y un reverse proxy. Mantener afinidad, correlation ID y autenticación al cambiar de 0 a 25 a 100 %. Ejecutar rollback, comprobar comportamiento y medir errores/latencia. No duplicar solicitudes con efectos.

7. **Expand & Contract.** Ejecutar el SQL de ejemplo en una base desechable. Insertar filas con el escritor anterior durante la expansión; actualizar al escritor que rellena ambos campos, terminar el backfill, retirar el anterior y validar. No ejecutar la contracción con clientes antiguos activos.

8. **Contrato de consumidores.** Validar JSON real contra el schema v1. Aceptar un nuevo campo opcional, rechazar versiones desconocidas y probar el extremo de un entero de 64 bits sin pérdida de precisión. Publicar v2 en paralelo si cambia la semántica.

9. **Resiliencia de red.** Añadir jitter con random inyectable, timeout por intento, cancelación y deadline total. La suma de esperas e intentos debe respetar el presupuesto. Probar que un error de negocio produce una sola llamada y que un breaker no permite múltiples probes simultáneos.

10. **Observabilidad operacional.** Sustituir contadores acumulados por ventanas e histogramas. Alertar por errores, p95 y edad del outbox con tráfico suficiente. Probar expiración de una ventana, una ráfaga y ausencia de tráfico; comprobar que no se registran tokens ni datos personales.

11. **Disponibilidad y tenants.** Crear dos stamps con capacidades independientes. Saturar uno y comprobar que el otro conserva servicio. Simular caída del consumidor de analítica: las cotizaciones deben seguir y la proyección debe converger al recuperar el consumidor.

12. **Identidad y fiabilidad.** Añadir un adapter OIDC y almacenamiento de contenido inmutable. Un token expirado o con audiencia incorrecta debe fallar antes de gastar cuota. El mismo contenido canonicalizado debe producir el mismo hash y una corrupción debe detectarse al leer.

Cada extensión debe implementarse en ambos lenguajes y añadir únicamente escenarios que prueben sus invariantes y modos de fallo nuevos.
