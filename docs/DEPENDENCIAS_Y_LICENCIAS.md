# Dependencias verificadas

Las versiones están fijadas en `gradle/libs.versions.toml`; no hay versiones dinámicas. Se verificaron documentación oficial y metadatos descargados de Google Maven/Maven Central. Las herramientas se instalaron en el proyecto, sin cambiar el sistema global.

| Componente directo | Versión | Licencia/procedencia |
|---|---|---|
| Android Gradle Plugin | 9.3.3 | Apache 2.0, Google |
| Gradle Wrapper | 9.5.0 | Apache 2.0, Gradle |
| Kotlin y plugin Compose | 2.4.21 | Apache 2.0, JetBrains |
| KSP | 2.3.14 | Apache 2.0, Google |
| Compose BOM | 2026.09.00 | Apache 2.0, AndroidX |
| Core KTX | 1.19.1 | Apache 2.0, AndroidX |
| Activity Compose | 1.13.0 | Apache 2.0, AndroidX |
| Lifecycle | 2.11.0 | Apache 2.0, AndroidX |
| Room | 2.8.5 | Apache 2.0, AndroidX |
| MapLibre Android OpenGL | 13.5.4 | BSD 2-Clause y avisos de terceros, MapLibre |
| OkHttp | 5.5.1 | Apache 2.0, Square |
| Gson | 2.15.0 | Apache 2.0, Google |
| Kotlin coroutines | 1.11.0 | Apache 2.0, JetBrains |
| Google Play services Location | 21.4.0 | Google; términos del SDK de Google Play services, no licencia del SDK de mapas |
| AndroidX Fragment | 1.9.1 | Apache 2.0; actualiza la dependencia transitiva de Google Play para compatibilidad con ActivityResult |
| JUnit 4 | 4.13.2 | EPL 1.0 |
| AndroidX Test JUnit / Runner | 1.3.0 / 1.7.0 | Apache 2.0 |

Se usan Android SDK plataforma 37.0, Build Tools 36.0.0 y Java de Android Studio JBR 21.0.8. La APK tiene `minSdk=26`, `targetSdk=37`, `compileSdk=37`. AGP 9 trae soporte Kotlin integrado, por lo que no se aplica el plugin `kotlin.android` duplicado.

El analizador lint se fija en 9.5.1 mediante la opción oficial `android.experimental.lint.version`. La versión incluida en AGP 9.3.3 se detenía al recorrer el código Kotlin; con lint actualizado el análisis terminó sin desactivar controles. El script adicional `scripts/check-source.ps1` rechaza controles Unicode bidireccionales en el código. La elección de AGP/Gradle prioriza una combinación compatible; MapLibre 13.5.4 es una rama con correcciones de mantenimiento recientes. Las advertencias de versiones más nuevas no significan automáticamente vulnerabilidad o incompatibilidad.

Gradle se descargó por HTTPS y se comprobó su SHA-256 publicado. `gradle-wrapper.properties` conserva el checksum para futuras descargas. El detalle resuelto de bibliotecas se conserva en los informes de entrega; las dependencias transitivas pueden subir una versión mediante reglas de resolución de Gradle.

## Fuentes y licencias

- Compatibilidad AGP 9.3 y correcciones: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Kotlin/Gradle: https://kotlinlang.org/docs/gradle-configure-project.html
- Kotlin integrado de AGP: https://developer.android.com/build/migrate-to-built-in-kotlin
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Room: https://developer.android.com/jetpack/androidx/releases/room
- AndroidX: https://developer.android.com/jetpack/androidx/versions
- Fragment: https://developer.android.com/jetpack/androidx/releases/fragment
- KSP: https://github.com/google/ksp/releases
- MapLibre y avisos de licencia: https://github.com/maplibre/maplibre-native y https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md
- OkHttp: https://github.com/square/okhttp
- Gson: https://github.com/google/gson
- Coroutines: https://github.com/Kotlin/kotlinx.coroutines
- Ubicación Google Play services: https://developers.google.com/android/guides/setup y https://developers.google.com/android/guides/overview
- JUnit: https://junit.org/junit4/license.html
- Licencia Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0

## Cartografía

MapLibre es el motor de representación. El servicio cartográfico utilizado es OpenFreeMap, con estilo Liberty, datos OpenMapTiles/OpenStreetMap. La atribución permanece visible en el mapa. OpenStreetMap utiliza ODbL y requiere atribución; utilizar un motor abierto no elimina las condiciones de los datos y proveedores.

OpenFreeMap permite descargas móviles de hasta 3.000 teselas o 50 MB por descarga, máximo cuatro solicitudes paralelas y User-Agent propio. La implementación aplica márgenes menores, región pequeña y límites de red/almacenamiento. La autorización y condiciones consultadas están en https://openfreemap.org/quick_start/ . No se usa `tile.openstreetmap.org` para descargar regiones. Política OSM: https://operations.osmfoundation.org/policies/tiles/ . Atribución: https://www.openstreetmap.org/copyright .

Las condiciones del servicio deben revisarse antes de ampliar volumen o distribuir a muchos usuarios. No se contrató ningún servicio de pago. Los términos de distribución del código propio se deben definir por el propietario; este documento enumera dependencias, no asigna una licencia pública al proyecto.
