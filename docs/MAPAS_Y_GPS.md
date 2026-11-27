# Mapas y seguimiento GPS

## Implementación

- MapLibre Native OpenGL representa los mapas, sectores, puntos comerciales, borrador de polígono y cada segmento GPS guardado. El mapa no une segmentos separados por pausa, recuperación o falta de señal.
- Desde 0.3.0, `TrackingService` usa Fused Location Provider cuando hay Google Play Services disponible, con alternativa `LocationManager` si falta o falla el registro del proveedor combinado. Esto no integra el SDK de Google Maps ni requiere clave de Maps. Solo empieza por una acción explícita Iniciar/Reanudar. No hay receptor de arranque ni reinicio automático del servicio (`START_NOT_STICKY`).
- La notificación del servicio tiene Pausar y Finalizar. Estas acciones retiran las solicitudes de ubicación antes de esperar a la base de datos.
- Se piden ubicación precisa y notificaciones. El servicio se inicia desde la interfaz visible y declara tipo `location`; no requiere permiso permanente de ubicación en segundo plano.
- El recorrido solicita alta precisión cada 6 segundos (antes 3), sin ubicaciones iniciales de caché ni lotes diferidos. Google combina las señales disponibles; si se usa la alternativa de Android se solicita únicamente GPS. Android decide la frecuencia efectiva. Las mismas comprobaciones peatonales se aplican a ambos proveedores. Menos solicitudes no equivale a una reducción proporcional del consumo: el hardware puede mantener GNSS activo.
- `WalkingGpsFilter` espera tres lecturas durante al menos seis segundos, rechaza precisión estimada mayor a 25 m y exige dos posiciones coherentes separadas por al menos cuatro segundos para confirmar movimiento. El radio de quietud es el mayor de 8 m y 1,2 veces la incertidumbre combinada; las dos lecturas de confirmación deben separarse al menos 3 m. Desde 0.3.0 una lectura mala o un salto aislado se descartan sin reiniciar todo el filtro. Más de 30 segundos sin lecturas fiables, un cambio de reloj o una pausa separan el segmento y requieren estabilización. Una parada prolongada con lecturas fiables continuas no se confunde con pérdida de señal. No se interpolan coordenadas ni se ajustan artificialmente a calles.
- El filtro es conservador: puede omitir movimientos cortos, curvas cerradas y metros al comenzar. No garantiza eliminar toda deriva ni medir interiores. La precisión que informa Android es una estimación, no la posición real comprobada. Los datos históricos se conservan sin modificar.
- Cada 10 segundos se persiste una señal de actividad. Al abrir la interfaz sin servicio activo, la recuperación corta el intervalo activo en la última señal persistida y deja la jornada pausada, con incidencia. No se contabiliza como trabajo el período en que el proceso estuvo muerto.
- Los puntos recibidos desde la caché de Android anteriores al comienzo o a la última reanudación se descartan. Las transiciones del servicio se serializan para impedir que una pausa pendiente apague una reanudación posterior.
- Desde 0.4.0, Mi ubicación comprueba el interruptor de ubicación mediante SettingsClient y ajustes Android como alternativa. La consulta puntual usa Fused lastLocation/updates cada 2 s con espera inicial desactivada, alternativa LocationManager si no hay resultado inicial y un plazo total de 20 s. Se aceptan posiciones para orientación de hasta 30 s y precisión estimada hasta 500 m; ambos relojes deben ser recientes. El resultado aproximado se mejora y termina antes si llega una lectura de hasta 20 m y menos de 10 s. Se retiran listeners y callbacks al terminar y al iniciar/pausar/finalizar jornada. No se persiste la consulta puntual como recorrido.
- El punto azul puede representar una lectura más aproximada que el filtro del recorrido; el círculo muestra incertidumbre y el punto gris indica más de 30 s sin lectura reciente. La cámara sigue durante la jornada; tocar/explorar o elegir zona suspende el seguimiento. Mi ubicación lo reactiva y abandona una selección de zona todavía no guardada. El mapa base no modifica ni mejora la precisión del receptor GPS.
- Los puntos rosados representan entregas a un toque. Solo se les asigna ubicación si tiene hasta 30 s y precisión estimada hasta 100 m; de otro modo quedan sin coordenadas. No verifican presencia en un negocio. Zonas con colores persistentes, relleno suave y borde seleccionado destacado.
- La pantalla principal del mapa no está dentro de un contenedor desplazable. El mapa conserva sus gestos; el reloj se actualiza por separado y solo se reconstruyen fuentes de dibujo cuyos datos cambiaron. El selector usa cuatro esquinas ajustables proyectadas a coordenadas y guarda un sector con las revisiones existentes.
- Las ubicaciones simuladas, cuando Android las identifica, se etiquetan. Esa señal no prueba fraude ni la entrega efectiva de publicidad.

## Descargas autorizadas

Fuente online: `https://tiles.openfreemap.org/styles/liberty`. Según la [guía oficial de OpenFreeMap](https://openfreemap.org/quick_start/) consultada el 17/11/2026, permite descargas móviles offline de hasta 3.000 teselas o 50 MB por descarga, con un máximo de cuatro solicitudes simultáneas y User-Agent propio.

La aplicación usa el User-Agent `RutaComercial-Android/1.0 (com.rutacomercial.app)` y un único cliente HTTP limitado a cuatro solicitudes globales y por servidor. Cada descarga explícita abarca el rectángulo del sector elegido, hasta 1 km por lado, o 1 km × 1 km alrededor de un punto si no hay sector. Usa niveles 12–16, sin glifos CJK. Un presupuesto compartido limita cada intento a 2.500 solicitudes y 45 MB de cuerpos HTTP; el observador interrumpe la región al alcanzar 2.400 teselas o 40 MB almacenados. Los márgenes contemplan las peticiones concurrentes. Los bytes incluyen otros recursos del mapa que se soliciten durante la descarga, por prudencia. Al fallar se pausa: los reintentos requieren intervención del usuario.

Solo una región descargada por completo se marca como disponible sin conexión. Las regiones incompletas se pueden reanudar o eliminar. Se almacenan en la base offline privada de MapLibre, independiente de los registros comerciales. No se realiza ninguna descarga desde `tile.openstreetmap.org`; su [política](https://operations.osmfoundation.org/policies/tiles/) prohíbe el uso offline masivo.

Las condiciones del proveedor pueden cambiar. Antes de publicar comercialmente o ampliar los límites debe revisarse su autorización. Para regiones mayores, una ampliación posible es importar PMTiles locales generados a partir de fuentes autorizadas; **esta importación no está implementada**. [Soporte nativo PMTiles](https://github.com/maplibre/maplibre-native/blob/main/platform/android/docs/data/PMTiles.md), [descargas Protomaps](https://docs.protomaps.com/basemaps/downloads).

## Limitaciones y comprobaciones pendientes en teléfono

- Android y cada fabricante pueden terminar el proceso o restringir el GPS con pantalla apagada. No se garantiza continuidad ante cierre forzado, batería agotada o restricciones del sistema.
- El intervalo de 6 segundos es una solicitud, no una promesa de recepción. La duración real de batería debe medirse en el dispositivo del propietario, incluyendo el POCO X6 Pro con pantalla apagada. El porcentaje mostrado en el apartado de uso por aplicación no basta para calcular puntos porcentuales de carga por hora. Anotar carga inicial/final, duración, conectividad y configuración de ahorro permite comparar.
- El punto de cámara inicial en Buenos Aires sirve para orientar el mapa; **no representa una ubicación observada**. Mi ubicación requiere una medición real.
- No hay enrutamiento peatonal calculado. Los puntos, polígonos y segmentos observados no constituyen una ruta optimizada ni prueba de visita o entrega.
- Si falla la carga cartográfica, se muestra una advertencia y un fondo neutro donde siguen disponibles los datos locales. La carga se reintenta al recuperar internet; también se puede volver a abrir la pantalla Mapa. Un mapa cargado puede mostrar zonas sin teselas al salir de la región descargada.
- Los mapas offline no se incluyen en el respaldo comercial; se pueden volver a descargar. El registro GPS y las visitas funcionan aunque no haya mapas.
- Deben probarse físicamente inicio/pausa/reanudación/cierre, pantalla apagada, revocación de permisos, modo avión, GPS desactivado, recuperación del proceso y consumo de batería. Las pruebas JVM no demuestran comportamiento real del sensor.

## Pruebas incluidas

`DownloadBudgetTest` comprueba los límites de solicitudes y bytes. `TrackingServiceTest` ejecuta en Android la secuencia iniciar, inyectar ubicaciones en modo simulado de Fused Location Provider, pausar, comprobar que no se guardan puntos, reanudar con un segmento nuevo y finalizar; comprueba además la notificación, el proveedor utilizado y la persistencia. `JourneyLocationSourceTest` comprueba la alternativa GPS de Android cuando no hay Google Play y la retirada de la suscripción al detenerla. El modo simulado y el proveedor de prueba se retiran al terminar; las posiciones quedan marcadas como simuladas.

La prueba de permiso denegado de ese archivo verifica la barrera del controlador mediante un contexto que informa permiso rechazado. **No prueba el diálogo de permisos de Android ni la revocación del sistema**, porque revocar permisos puede terminar el proceso que ejecuta la instrumentación. La revocación/reapertura y la muerte del proceso requieren una prueba externa por ADB o el teléfono. El resultado de ejecución real de estas pruebas se informa en el documento general de validación, no se presume aquí.

Referencias técnicas: [servicios de ubicación en primer plano](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [MapLibre OfflineManager](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/index.html), [ciclo de vida MapView](https://maplibre.org/maplibre-native/android/examples/getting-started/).

## Ubicación compartida desde Google Maps (0.3.0)

Google Maps → buscar lugar o mantener pulsado para poner un pin → Compartir → RutaComercial. La app muestra el punto recibido y permite abrir el selector de zona a su alrededor. El usuario revisa el mapa y confirma **Usar esta zona**. Recibir un enlace nunca inicia una jornada ni registra un punto como GPS observado.

También está **Opciones → Traer ubicación de Google Maps** para pegar un enlace o coordenadas. No se lee el portapapeles automáticamente. Los enlaces cortos requieren internet; se siguen hasta cinco redirecciones HTTPS de dominios de Maps permitidos, con límite total de 15 segundos. Se extraen coordenadas explícitas, dando prioridad al pin frente al centro de cámara. Enlaces de rutas, búsquedas sin coordenadas, múltiples lugares o identificadores opacos se rechazan con instrucciones para compartir un pin. No se analiza HTML ni se consulta Places. Los enlaces que solo describen la vista se identifican como aproximados y requieren revisión. El mapa interno y sus descargas siguen siendo OpenFreeMap; no se importan negocios, zonas amarillas ni mapas offline de Google.

Documentación: [recepción de contenido compartido en Android](https://developer.android.com/develop/ui/compose/sharing/receive), [ubicación y batería](https://developer.android.com/develop/sensors-and-location/location/battery), [configurar Google Play services](https://developers.google.com/android/guides/setup), [LocationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder).


## Guía que descuenta lo recorrido

La Guía arma un circuito que pasa por cada cuadra una vez en cada sentido, que equivale a una vereda por sentido. Al abrirla, descuenta las cuadras que los trazos de la zona ya cubrieron en ese mismo sentido (a 20 m o menos, en al menos el 60 % de la cuadra) y muestra cuánto falta. Usa el sentido de la marcha porque el GPS del teléfono no distingue de qué vereda viene la señal.

## Ajustar la zona a lo recorrido

En el mapa del Owner, **Opciones → Ajustar zona a lo recorrido** propone un borde alrededor de los trazos de la zona, con 15 m de margen. Se puede guardar, redibujar a mano o cambiar a rectángulo antes de tocar **Usar esta zona**. El borde sugerido es convexo: en un recorrido en L incluye la esquina interior y conviene recortarlo.

## Dirección del abanico

El abanico combina la brújula del teléfono (vector de rotación: giroscopio, acelerómetro y magnetómetro) con el rumbo que mide el GPS al caminar. Con el teléfono vertical mirando una calle, la app usa la dirección de la cámara trasera. Mientras caminás, aprende cuánto se desvía la brújula respecto del GPS (metal, autos, edificios) y aplica esa corrección cuando te detenés. Si la brújula está imprecisa, el mapa pide moverla en forma de 8 y usa el rumbo del GPS.

## Respaldo

El respaldo cifrado (versión 3) incluye también las tareas, los informes recibidos de los repartidores y las propuestas. Los respaldos anteriores se siguen restaurando, sin esa parte.
