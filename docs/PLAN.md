# RutaComercial: plan de implementación

Proyecto independiente en `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android`, la carpeta de trabajo autorizada en esta sesión. No se utilizará CreditApp ni se harán commits, publicaciones o servicios de pago.

## Inspección inicial (17/11/2026)

Windows con Android Studio 2025.2.2, JBR 21.0.8, SDK Android 35, herramientas de compilación 35–37 e imagen de emulador Android 36. Java, Gradle y adb no están en PATH. Se usarán rutas explícitas y cachés locales del proyecto; no se cambiará la configuración global. La red del entorno restringido requiere autorización automática para descargar herramientas oficiales. No hay código previo.

## Arquitectura validada

Android nativo, Kotlin, Compose y Material 3. MVVM con estado observable, repositorio y Room transaccional. Servicio foreground de ubicación iniciado por el propietario, notificación persistente con parada, puntos segmentados ante pausas y pérdida de señal. MapLibre Native con OpenFreeMap y límites de descarga autorizados. UUID y movimientos inmutables/idempotentes preparan una futura sincronización, sin servidor ni cuentas simuladas. Sin datos de demostración.

## Hitos

1. Proyecto compilable, navegación, permisos, mapa, servicio GPS y persistencia de jornadas.
2. Sectores con revisiones, negocios, visitas declaradas, campañas, inventario auditable y planificación manual.
3. Regiones sin conexión, exportaciones CSV/GPX/GeoJSON, respaldo cifrado sin sobrescritura, resultados derivados y pruebas.
4. Compilación APK debug, pruebas locales/instrumentadas si el emulador arranca, informes de evidencia y guía de instalación.

## Criterios de comprobación

Pruebas de filtros GPS y segmentos, transiciones de jornada, idempotencia e inventario, persistencia Room, restauración íntegra y rechazos. Compilación y análisis estático. Pruebas del emulador claramente diferenciadas de las pendientes en teléfono físico. Cada función incompleta se documentará como pendiente, nunca como implementada.

## Resultado de esta ejecución

Se instaló SDK 37.0 y Gradle 9.5.0 dentro del proyecto. La APK debug se compiló e instaló en el emulador aislado. Pasaron 31 pruebas JVM y 24 instrumentadas, incluida cartografía offline real. Lint final terminó sin errores usando el analizador oficial 9.5.1. La firma y el SHA-256 están verificados. El alcance y las limitaciones de campo se detallan en `FUNCIONES_IMPLEMENTADAS_Y_PENDIENTES.md` y `PRUEBAS.md`.

## Actualización 0.2.0 — 18/11/2026

Por solicitud del propietario, se simplificó el inicio al mapa, se separaron sus gestos del desplazamiento de formularios y se añadieron zonas rectangulares ajustables a escala de cuadras, descargas del sector y consulta opcional en Google Maps externo. Se incorporó un filtro peatonal temporal y se mantuvieron intactos los registros anteriores. Las pruebas y el paquete actualizado están en `PRUEBAS.md` y `dist/RutaComercial-0.2.0-debug.apk`. No se contrataron APIs ni se realizaron commits o publicaciones.

## Actualización 0.3.0 — 18/11/2026

Tras la prueba del propietario con un POCO X6 Pro y pantalla principalmente apagada, se incorporó recepción de ubicaciones compartidas de Google Maps, tolerancia a lecturas GPS malas aisladas y separación entre quietud y pérdida de señal. El seguimiento solicita ubicación combinada cada 6 segundos con alternativa GPS Android. Se mantiene la confirmación explícita de la zona y la jornada, sin cuentas, claves de Maps ni facturación. Los resultados automatizados y los límites de la medición física se registran en `PRUEBAS.md`; el consumo real sigue requiriendo carga inicial/final en una caminata comparable.
