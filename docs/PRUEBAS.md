# Actualización 0.5.0 — 22/11/2026

APK de pruebas `dist/RutaComercial-0.5.0-debug.apk`. Firma de actualización verificada. El tamaño y SHA-256 figuran en `dist/APK_INFO.txt`.

- Pasaron 55 pruebas JVM, incluidas paradas, detección de ritmo alto y cuadrícula. Lint terminó con 0 errores, 16 advertencias y 4 sugerencias.
- Pasaron 7 pruebas Android en emulador. Se verificaron el trazo libre, el guardado de la zona, los gestos del mapa, el seguimiento de posición y el flujo comercial.
- El GPX y GeoJSON del 21/11 coinciden en 404 posiciones de un tramo. La distancia entre puntos es 8,44 km y el tiempo entre la primera y última posición es 2 h 52 min, con una media global de 2,95 km/h. Los archivos exportan puntos filtrados. Las métricas nuevas de paradas y ritmo sostenido usan lecturas de la jornada que esas exportaciones no contienen.
- Queda por verificar en el POCO X6 Pro la respuesta del sensor de orientación, la legibilidad en calle y el consumo real con pantalla apagada.

Evidencias en `evidencias/compilacion-0.5.0-final.log` y `evidencias/campo-0.5.0.log`.

---

# Actualización 0.4.0 — 21/11/2026

APK de pruebas `dist/RutaComercial-0.4.0-debug.apk`. Firma de actualización verificada contra la versión 0.3.1. Tamaño y suma SHA-256 en `dist/APK_INFO.txt`.

Pasaron 52 pruebas JVM. El análisis estático terminó con 0 errores, 16 advertencias y 3 sugerencias. La suite Android inicial aprobó 34 de 36 casos. Dos casos fallaron al sincronizar la reanudación GPS y la aparición del botón Deshacer. Se reinició el estado del proveedor al iniciar la suscripción y se ajustaron las esperas de prueba. En el APK definitivo se repitieron tres casos relacionados y pasaron todos, incluidos los dos pendientes. Se conservan los resultados de ambas ejecuciones en `evidencias/instrumentacion-0.4.0.log` y `evidencias/instrumentacion-0.4.0-reprueba.log`.

Se comprobó la migración con el esquema publicado v1 y datos anteriores, colores distintos, respaldos cifrados de ambas versiones, control de stock, deshacer y entrega sin negocio ni GPS. En un emulador con ubicación apagada, abrir la app mostró el mapa y pulsar Mi ubicación abrió el diálogo nativo de activación. Al aceptar se recibió una posición simulada, y se verificó visualmente el punto centrado y su cambio de color por antigüedad. Capturas en `capturas/activar-ubicacion-0.4.0.png` y `capturas/mi-ubicacion-0.4.0.png`. El margen de 5 m de la captura pertenece al emulador. Queda pendiente medir precisión y batería en una caminata con el POCO X6 Pro.

---
# Actualización 0.3.1 — 20/11/2026

APK: `dist/RutaComercial-0.3.1-debug.apk`, versión **0.3.1 (4)**, **55.850.200 bytes**.
SHA-256: `443295E6619FF52FB9709855CA9E070AABCAA22F12CE1A1A63512CCF051487ED`.
Firma verificada: misma clave que 0.3.0. No cambia el esquema de datos; instalar encima sin desinstalar.

- Compilación aprobada; **51 pruebas JVM aprobadas**.
- **8 pruebas Android aprobadas**: apertura sin solicitud GPS, activación/suspensión/reactivación del centrado, cambio de cámara con posiciones nuevas sin pulsar Mi ubicación, arrastre real de esquinas y mapa, permisos, marcha/quietud/pausa/reanudación/finalización, creación de sector y flujos comerciales/respaldo de interfaz.
- Lint: **0 errores, 13 advertencias y 3 sugerencias**. Las advertencias son de versiones disponibles y preferencias KTX; no se ocultaron.
- La prueba de cámara comprueba el destino geográfico informado por MapLibre. No es una caminata física ni una medición de precisión o batería. Las pruebas offline y de interrupción externa de 0.3.0 no se repitieron en esta corrección; no se presentan como nuevas pruebas de 0.3.1.

Evidencia: `compilacion-0.3.1.log`, `instrumentacion-0.3.1.log`, `firma-apk-0.3.1.txt`, `lint-0.3.1.*`, `jvm-0.3.1/TEST-*.xml`.

La entrega rápida y el seguimiento de otras personas desde WhatsApp se mantienen como propuestas. El mapa base continúa siendo OpenFreeMap; traer coordenadas no trae la cartografía ni los negocios de Google Maps.

---
# Actualización 0.3.0 — 18/11/2026

APK: `dist/RutaComercial-0.3.0-debug.apk`, versión **0.3.0 (3)**, **55.850.200 bytes**.
SHA-256: `A85F9C041C805459C53E81221D5326500B82A6A37B61D266A2DAC6F7C68341E0`.
La firma coincide con 0.2.0 y 0.1.0; se conserva el esquema Room y el identificador de aplicación. Instalar encima, sin desinstalar.

- **51 pruebas JVM aprobadas**: reglas existentes, filtro peatonal, precisión mala aislada frente a sostenida, salto aislado, continuidad después de una parada con señal fiable y seis casos de enlaces compartidos (coordenadas, pin frente a cámara, rutas ambiguas, dominios, redirecciones y enlaces sin punto recuperable).
- **29 casos instrumentados aprobados** en total sobre Android 16/API 36: la suite final aprobó 28 y la repetición específica del gesto aprobó el caso restante. Incluyen Fused Location Provider en modo simulado, alternativa GPS sin Google Play, pausa/reanudación/finalización, recepción de enlaces en actividad abierta, confirmación de zona alrededor del pin, enlaces rechazados sin crear datos, respaldo/inventario y descarga/renderizado real de OpenFreeMap sin conexión.
- **Compilación y lint aprobados**: 0 errores, 9 advertencias y 3 sugerencias. Las advertencias conocidas no se ocultaron. Google Play traía una dependencia antigua de Fragment; se fijó AndroidX Fragment 1.9.1 compatible con ActivityResult.
- **9 verificaciones externas ADB aprobadas**: cinco puntos persistidos antes del cierre forzado, registro con pantalla apagada y sin internet, recuperación en pausa con los mismos cinco puntos, incidencia de interrupción, pausa al revocar el permiso y segmento separado al reanudar. El primer intento no produjo un punto confirmado durante una ventana offline de solo 12 segundos; el caso definitivo usa posiciones cada 8 segundos y cuatro posiciones sin conexión para contemplar el nuevo intervalo y la confirmación temporal. No se cambió el código de la APK entre esos intentos.
- **Compartir con la app cerrada aprobado**: Android resolvió ACTION_SEND mediante el manifiesto, se mostró la revisión y se guardó una sola zona alrededor del pin (centro a 16 m del punto compartido, dentro del recuadro de 263 × 352 m). No se creó ninguna jornada ni punto GPS. Capturas en `capturas/ubicacion-compartida-0.3.0.png` y `capturas/zona-desde-google-0.3.0.png`.

La primera instrumentación detectó que reemplazar el Intent de lanzamiento impedía al monitor de ActivityScenario reconocer el cierre. La app conserva ahora su identidad de lanzamiento y guarda el contenido compartido en estado UI. Las pruebas de compartir pasaron sobre esta APK. El gesto nativo quedó bloqueado por un aviso de **System UI no responde del emulador**, visible en la captura de diagnóstico; tras cerrarlo, el arrastre de esquinas y el desplazamiento del mapa pasaron usando el reloj real de Android en ambos gestos. No se desactivó el detector de errores del sistema ni se alteró el mapa para ocultar el problema.

Evidencia: `compilacion-entrega-0.3.0.log`, `instrumentacion-final-0.3.0.log`, `gestos-final-0.3.0.log`, `dispositivo-final-0.3.0.log`, `dispositivo-0.3.0.json`, `compartir-0.3.0.log`, `compartir-0.3.0.json`, `firma-apk-0.3.0.txt`, `lint-0.3.0.*` y `jvm-0.3.0/TEST-*.xml`. Se conservan los intentos anteriores para explicar los ajustes. Los enlaces cortos se validan con respuestas de red controladas en JVM; no se afirma compatibilidad con todas las variantes actuales de enlaces de Google Maps.

**Pendiente físico:** consumo y precisión en POCO X6 Pro. El usuario probó 0.2.0 durante 1 h 42 min con pantalla principalmente apagada, pero no recuerda carga inicial/final. El dato de 12,67 % mostrado por Android no establece por sí solo puntos de carga consumidos por hora. No se afirma un ahorro porcentual ni continuidad garantizada bajo las restricciones de Xiaomi. Probar 30–60 minutos con ajustes comparables y carga inicial/final anotada. El proveedor combinado mantiene alta precisión; reducir solicitudes de 3 a 6 segundos no garantiza reducir a la mitad la actividad de GNSS. Los recorridos históricos se conservan.

---
# Actualización 0.2.0 — 18/11/2026

APK: `dist/RutaComercial-0.2.0-debug.apk`, versión **0.2.0 (2)**, **55.543.622 bytes**.
SHA-256: `644964FD6B7EE72D755E34D6C3258E70D06EE7156E93ACCDA67BA15A843C2890`.
La firma se verificó y coincide con 0.1.0. Puede instalarse encima de aquella versión conservando la base; no desinstalar para actualizar.

- **42 pruebas JVM aprobadas**, sin fallos: 31 anteriores, 8 del filtro peatonal y 3 de límites geográficos de descarga.
- **26 pruebas instrumentadas aprobadas** en RutaComercialTest, Android 16/API 36. Incluyen arrastre Android real del mapa, ajuste de esquinas, guardado de zona sin crear jornada, registro comercial, filtro de quietud, pausa/reanudación y descarga/renderizado offline de un sector real de OpenFreeMap.
- **9 verificaciones ADB aprobadas** para el cambio de seguimiento de 0.2.0 (antes del ajuste final de zoom, que no modifica GPS): cuatro puntos GPS aceptados y persistidos; registro con pantalla apagada y sin internet; pausa tras cierre del proceso; conservación exacta de puntos; incidencia de interrupción; pausa tras revocar el permiso y nuevo segmento al reanudar.
- Compilación, pruebas y lint completados: **0 errores de lint, 9 advertencias y 3 sugerencias**. Se conservan las advertencias de versiones/KTX; no se introdujo una línea base para ocultar errores.
- La APK incluye las arquitecturas arm64-v8a, armeabi-v7a, x86 y x86_64. La prueba ejecutada fue x86_64; no se afirma ejecución física en todas las arquitecturas.

Evidencia de esta actualización en `docs/evidencias`: `compilacion-0.2.0.log`, `instrumentacion-0.2.0.log`, `gestos-final-0.2.0.log`, `dispositivo-0.2.0.json`, `dispositivo-0.2.0.log`, `firma-apk-0.2.0.txt`, `lint-0.2.0.*` y `jvm-0.2.0/TEST-*.xml`.

La primera pasada detectó una prueba que enviaba al mapa eventos de reloj virtual y un campo de coordenadas oculto en la entrada manual. Se corrigió el envío de gestos por Android y la entrada manual. La suite de 26 casos pasó; después del último ajuste del mensaje de Mi ubicación se repitieron los cuatro casos de interfaz sobre la APK de entrega (interfaz-final-0.2.0.log). La prueba de gestos también tolera el intervalo en que la cámara se mueve y la selección todavía no está disponible.

**Pendiente físico:** caminar varias cuadras, detenerse y volver con el teléfono del usuario; comprobar fluidez, batería y trayectoria real. El filtro evita los casos simulados de deriva cubiertos por las pruebas, pero no garantiza precisión en interiores ni conserva todos los movimientos menores a su radio de quietud. Los recorridos antiguos no se reescriben.

---

# Resultados verificables — 17/11/2026

## APK de entrega

`C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android\dist\RutaComercial-0.1.0-debug.apk`

- Versión 0.1.0 (1), paquete `com.rutacomercial.app`.
- Tamaño: **60.427.026 bytes** (aproximadamente 60,43 MB / 57,63 MiB).
- SHA-256: `25171408EF6D66CFF0CE9EB1BCE6D277D210F26309E23CBC143E6E8367F96666`.
- Firma verificada con `apksigner`: esquema v2, certificado **Android Debug**. No es una firma de producción.
- Compilación `assembleDebug`, `testDebugUnitTest`, `lintDebug` y `assembleDebugAndroidTest`: **BUILD SUCCESSFUL**.

## Pruebas automatizadas

**31 pruebas JVM, 0 fallos y 0 errores**:

- 23 pruebas de reglas: GPS, posiciones inválidas, precisión, pausas, pérdida de señal, saltos, geometría, nombres y conciliación de inventario.
- 4 pruebas de cifrado y CSV: ida/vuelta, aleatoriedad, contraseña incorrecta, alteración, formato inválido y neutralización de fórmulas.
- 2 pruebas de exportaciones: GPX/GeoJSON válidos, segmentos separados y datos vacíos.
- 2 pruebas de límites de descarga.

**24 pruebas instrumentadas, todas aprobadas**, ejecutadas sobre la APK de entrega en el emulador aislado **RutaComercialTest**, Android 16/API 36, x86_64:

- 17 pruebas Room: transiciones y recuperación de jornada, atomicidad de visita/stock, reintentos concurrentes, saldos, duplicados/fusión, cadenas de fusiones, revisiones de polígonos, no volver a visitar, puntos anteriores al inicio, segmentos GPS, restauración, planificación, cierre/reapertura del archivo SQLite y retención.
- 2 pruebas de respaldo: exportación cifrada/restauración, saldos recuperados, rechazo sin sobreescribir y archivo alterado sin escribir registros.
- 2 pruebas del servicio: permisos rechazados en un contexto controlado y ciclo real del servicio Android con proveedor GPS simulado. Comprueban notificación, guardado, pausa sin puntos nuevos, reanudación con segmento distinto, pausa/reanudación rápida y cierre sin servicio activo.
- 2 pruebas Compose: navegación/contraseña y flujo campaña → asignación → negocio → entrega de tres tarjetas, con saldo final comprobado y sin inventar un recorrido GPS.
- 1 integración cartográfica real: descarga de una región pequeña autorizada desde OpenFreeMap, consulta de persistencia nativa, desactivación de Wi-Fi/datos y renderizado de elementos vectoriales desde la región descargada. La prueba restaura la red y elimina su región temporal.

Los puntos simulados son únicamente datos de prueba en el emulador. La APK distribuida no contiene negocios, jornadas ni métricas de demostración.

## Análisis estático y revisión visual

La comprobación externa con ADB también terminó **PASSED**, con nueve verificaciones: seis puntos GPS persistidos, puntos registrados con pantalla apagada y sin internet, recuperación en pausa tras forzar el cierre, conservación exacta de los seis puntos sin interpolación, incidencia de interrupción, pausa tras revocar realmente el permiso de ubicación y nuevo segmento al reanudar. Se ejecutó sobre el mismo emulador; no es una prueba de batería o GPS físico.

Lint: **0 errores, 7 advertencias y 3 sugerencias**. Las advertencias restantes indican versiones de herramientas/mapas disponibles y preferencias de sintaxis KTX; las sugerencias proponen estados Compose especializados para enteros. No se ocultaron mediante una línea base ni se desactivaron detectores. La versión lint incluida en AGP 9.3.3 quedó bloqueada al recorrer comentarios Kotlin; se resolvió usando lint oficial 9.5.1. La ruta SDK de Windows se corrigió y el análisis final volvió a ejecutarse.

El script `check-source.ps1` comprobó ausencia de controles Unicode bidireccionales en el código. La pantalla inicial fue inspeccionada en emulador; captura en `capturas/inicio.png`. La instalación por ADB terminó con `Success`.

## Evidencia guardada

En `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android\docs\evidencias`:

- `compilacion.log`: ejecución Gradle final.
- `TEST-*.xml`: resultados unitarios.
- `instrumentacion.log`: resultado `OK (24 tests)` sobre la APK final.
- `lint.html` y `lint.txt`: informe completo.
- `firma-apk.txt`: verificación criptográfica del paquete.
- `dispositivo.json`: nueve verificaciones externas de interrupciones, todas aprobadas.

## Reproducir

Desde la carpeta del proyecto:

```powershell
.\scripts\build.ps1
```

Con el emulador aislado iniciado y las APK principal y de pruebas instaladas:

```powershell
$env:ANDROID_USER_HOME = "$PWD\.android"
.\.tools\android-sdk\platform-tools\adb.exe -s emulator-5556 shell am instrument -w -r -e offlineMapIntegration true com.rutacomercial.app.test/androidx.test.runner.AndroidJUnitRunner
```

La prueba offline requiere conexión al inicio y solo se habilita expresamente con `offlineMapIntegration=true`; se omite en teléfonos físicos. El script `scripts/device_smoke.py` comprueba además interrupciones externas y **borra únicamente los datos de RutaComercial del emulador aislado**; valida su nombre antes de actuar. No ejecutarlo como prueba sobre datos de trabajo.

## Pendiente en teléfono físico y otras versiones

No se realizaron caminatas reales ni mediciones de batería. Faltan pruebas de campo con pantalla apagada, ahorro de batería del fabricante, interiores/pérdida de señal, horas prolongadas de recorrido y reinicio completo del teléfono. La compatibilidad mínima API 26 está declarada y revisada por lint; la ejecución instrumental se realizó en API 36, no en todas las versiones ni dispositivos. No se afirma validación física completa ni disponibilidad comercial de producción.
