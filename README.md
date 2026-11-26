# RutaComercial Android

Aplicación Android nativa en español para distribuir publicidad, registrar jornadas GPS, delimitar sectores y gestionar negocios, visitas, campañas e inventario. Kotlin, Compose, Room y MapLibre. Uso individual y almacenamiento local, sin servidor obligatorio ni datos de demostración.

**Carpeta del proyecto:** `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android`

## Entrega

- APK de pruebas: `dist/RutaComercial-0.5.0-debug.apk`.
- En 0.5.0 el reparto se registra con el teléfono guardado. La zona admite trazo libre y el mapa incorpora brújula, abanico de rumbo y rótulos de calles más legibles. Historial resume paradas, ritmo y cuadrícula de zona. Las tarjetas opcionales están en **Mapa → Opciones → Registrar tarjetas**.
- Novedades 0.4.0: Mi ubicación ofrece activar la ubicación del teléfono, obtiene una orientación aproximada con plazo de 20 s y muestra precisión/antigüedad. Entrega a un toque con material recordado, deshacer, identificación posterior y conciliación de inventario. Zonas de distintos colores y borde destacado para la seleccionada. Actualización de base v1→v2 sin borrar registros; respaldos antiguos compatibles.
- [Evaluación de las propuestas cartográficas](docs/PROPUESTAS_CARTOGRAFICAS.md). La importación de comercios y la ubicación en vivo de WhatsApp siguen pendientes.
- Novedades 0.3.0: recibir un pin compartido de Google Maps para delimitar una zona, descartar lecturas GPS malas aisladas sin cortar el tramo, distinguir quietud de pérdida de señal y usar ubicación combinada cada 6 segundos con alternativa GPS para dispositivos sin Google Play. El ahorro de batería debe medirse en teléfono físico. No se añadieron cuentas ni servicios de pago.
- Tamaño, SHA-256 y firma: `dist/APK_INFO.txt`.
- [Cómo instalar y usar](docs/INSTALACION_Y_USO.md).
- [Funciones implementadas y pendientes](docs/FUNCIONES_IMPLEMENTADAS_Y_PENDIENTES.md).
- [Pruebas y resultados](docs/PRUEBAS.md).
- [Arquitectura](docs/ARQUITECTURA.md) y [contratos de datos](docs/DATA_API.md).
- [Mapas y GPS](docs/MAPAS_Y_GPS.md).
- [Dependencias y licencias](docs/DEPENDENCIAS_Y_LICENCIAS.md).
- [Riesgos y siguientes etapas](docs/RIESGOS_Y_SIGUIENTES_ETAPAS.md).

La APK es **debug**, no una distribución firmada para producción. Android mínimo: **8.0 (API 26)**. Una ruta observada no acredita que se haya realizado una entrega. Se debe validar el seguimiento y el consumo de batería en el teléfono que se utilizará.

## Compilación

```powershell
Set-Location -LiteralPath 'C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android'
.\scripts\build.ps1
```

El script ejecuta compilación, pruebas unitarias y lint; usa Gradle Wrapper y cachés locales. El proyecto se puede abrir con una versión de Android Studio compatible con AGP 9.3.3. La instalación de Android Studio encontrada es anterior; no fue modificada. La guía explica la compilación verificada por línea de comandos.

No se realizaron commits, pushes, publicaciones, despliegues ni conexiones con otros proyectos.
