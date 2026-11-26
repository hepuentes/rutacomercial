# Arquitectura y contratos de evolución

RutaComercial es una aplicación nativa Android local, de un propietario. No necesita un servidor para registrar información. No contiene cuentas de empleados, sincronización ni seguimiento remoto simulados.

## Capas

- `ui`: Compose/Material 3, navegación, formularios y ViewModel. El estado proviene de Flow de Room; no hay datos precargados.
- `data`: entidades, DAO, SQLite/Room y repositorio. Transacciones para visitas e inventario, validación centralizada y auditoría. El esquema se exporta en `app/schemas` y las futuras versiones deben incluir migraciones; no hay migración destructiva automática.
- `domain`: geometría, normalización de nombres, filtros GPS y conciliación de stock, comprobables sin un dispositivo.
- `tracking`: servicio de ubicación foreground, Fused Location Provider con alternativa LocationManager de Android y estado observable. Funciona sin claves comerciales y mantiene una alternativa para teléfonos sin Google Play Services.
- `maps`: MapLibre Native, representación de segmentos, polígonos y negocios; gestor de regiones de OpenFreeMap.
- `io`: exportaciones, respaldo portable cifrado y restauración validada.

Los contratos exactos de los modelos están en `DATA_API.md`. Los identificadores son UUID; fechas epoch en UTC. La interfaz muestra fechas locales. La exportación incluye fechas ISO 8601 UTC para evitar ambigüedad.

## Invariantes

Una sola jornada puede quedar activa o en pausa. La pausa y el cierre detienen el sensor. Si el proceso muere, la recuperación pausa al último latido persistido; el usuario debe reanudar. La interrupción no se convierte en recorrido ficticio. Cada punto tiene segmento, precisión, hora, reloj monotónico y señal de ubicación simulada. La geometría y la distancia nunca unen segmentos separados por pérdida de señal o pausa.

El depósito y las tarjetas en mano son saldos distintos. El stock inicial y las entradas aumentan el depósito; asignar traslada al material en mano; entregar y dañar reducen el material en mano; devolver lo reintegra. Una visita con tarjetas y su movimiento se guardan en la misma transacción. `operationId` y `visitId` evitan duplicación por reintentos. No hay saldos negativos. Las correcciones deben conservar el registro original y su trazabilidad.

Modificar un polígono crea una revisión. Las visitas conservan el sector declarado en ese momento. Fusionar negocios exige decisión explícita, conserva el negocio de origen marcado como fusionado y traslada el historial. La cercanía y el parecido del nombre solo producen una advertencia.

## Respaldo y privacidad

SQLite y fotografías se guardan en el directorio privado de la app. Su protección depende del aislamiento y cifrado de almacenamiento de Android y del bloqueo del teléfono; no se afirma cifrado SQLCipher adicional. Se desactiva la copia automática en la nube y transferencia automática del sistema. Una APK debug puede inspeccionarse desde un equipo autorizado para depuración: usar solo para pruebas y no habilitar ADB en equipos ajenos.

El respaldo `.rcb` lleva AES-256-GCM, sal y nonce aleatorios, derivación PBKDF2-HMAC-SHA256 de 600.000 iteraciones y contenido comprimido. La contraseña de al menos 12 caracteres no se almacena. La autenticación detecta cambios o contraseña incorrecta. Límites: 32 MB descomprimidos, 40 MB de archivo y 20 MB de fotografías. Se rechazan archivos incompletos, referencias inconsistentes y saldos inválidos. La restauración exige una base vacía y una única transacción; no sobreescribe registros existentes. No incluye teselas de mapas, que se vuelven a descargar.

Las fotos elegidas se copian al almacenamiento privado y se vuelven a codificar para quitar metadatos. Los CSV/GPX/GeoJSON exportados son archivos sin cifrar elegidos expresamente por el propietario; los valores CSV se escapan para evitar fórmulas al abrirlos en Excel.

La aplicación no registra visitas por pasar cerca de un comercio. Distingue recorrido observado, visita declarada, tarjetas declaradas y revisión humana. Las anomalías son avisos para revisión, nunca un veredicto de fraude.

## Servidor futuro, no implementado

Flask + PostgreSQL/PostGIS podrá conservar UUID existentes y agregar organización, usuario, rol, versión de registro y marcas de borrado. El teléfono incorporará una cola outbox en la misma transacción que los cambios locales, procesada por WorkManager. Se requiere TLS, autenticación individual, autorización en cada operación y separación de organizaciones.

Contrato propuesto de sincronización:

```json
{
  "protocol_version": 1,
  "device_id": "uuid",
  "batch_id": "uuid-idempotente",
  "operations": [{
    "operation_id": "uuid",
    "entity_type": "visit",
    "entity_id": "uuid",
    "base_revision": 1,
    "occurred_at": "2026-11-17T16:00:00Z",
    "payload": {}
  }],
  "pull_cursor": "opaque"
}
```

El servidor debe confirmar por operación y devolver conflictos explícitos. Visitas, movimientos y evidencias son eventos inmutables; el saldo se calcula desde el libro de movimientos, nunca mediante «última escritura gana». Un conflicto de inventario requiere revisión. Editar límites crea revisiones, no reemplaza geometrías históricas. Los cambios comerciales llevan revisión optimista. Los puntos GPS se suben por lotes con IDs originales y sin interpolación.

No se incluye WorkManager en esta versión porque no hay ningún trabajo remoto real que ejecutar. Se añadirá cuando exista servidor y un contrato validado.
