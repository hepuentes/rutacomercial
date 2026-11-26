# Evaluación de los tres adjuntos — 21/11/2026

Los adjuntos se evaluaron como propuestas; no son especificaciones ejecutables ni documentación oficial. La aplicación ya usa MapLibre Native, datos OSM y estilo Liberty de OpenFreeMap. No necesita reescribirse con Flutter o Leaflet para aprovechar esa base. [Guía oficial](https://openfreemap.org/quick_start/).

## Aplicado en 0.4.0

Se conservó esa cartografía. Se corrigió el acceso a ubicación Android, se añadió círculo de precisión, zonas distinguibles y entregas a un toque persistidas offline. Las visitas declaradas y los puntos de recorrido siguen separados. El historial GPS no se altera para aparentar una precisión que no tuvo.

## Próxima capa propuesta: comercios por zona

Una importación acotada de Overture Places puede enriquecer los comercios visibles. El proceso propuesto es extraer solo el municipio o zona necesaria, guardar identificador de origen, coordenadas, nombre/categoría, versión de publicación, confianza, procedencia y licencia; deduplicar contra los negocios del usuario y mostrar los candidatos como sugerencias antes de agregarlos a sus fichas. Una actualización nunca debe sobrescribir sus visitas o notas. La taxonomía actual usa basic_category/taxonomy; no asumir el antiguo campo categories de ejemplos encontrados en la web. No se garantiza cobertura completa ni que cada negocio siga abierto. [Datos, calidad, licencias y acceso](https://docs.overturemaps.org/guides/places/).

Esta capa no fue importada en 0.4.0: requiere elegir un extracto y comprobar cobertura en Mendoza. Es preferible una prueba local pequeña antes de sumar fuentes duplicadas de todo el país. Las fuentes municipales o nacionales pueden complementar direcciones/límites, pero cada conjunto necesita comprobar actualización y licencia.

## Rutas y seguimiento

Valhalla puede ajustar coordenadas a caminos cartografiados. Sería una capa opcional de ruta estimada para orientación, conservando los puntos originales por separado. La documentación describe ajuste a la red, no verificación de la trayectoria real ni de entregas. No se añadió un servidor de rutas. [Map matching oficial](https://valhalla.github.io/valhalla/api/map-matching/).

Si el error del GPS abarca dos veredas, no permite distinguirlas con certeza. El filtro peatonal puede omitir vueltas cortas mientras confirma movimiento. Para cobertura por acera convendría un marcador manual explícito de lado recorrido, independiente de la observación GPS. Una geocerca con tiempo de permanencia tampoco demuestra que se entregó una tarjeta.

Traccar/OwnTracks y PostGIS tendrían sentido al incorporar equipos y sincronización, no son necesarios para guardar recorridos locales de un repartidor. No se desplegaron servicios ni cuentas en esta actualización.

## Costos y límites

Código abierto no elimina alojamiento, almacenamiento ni mantenimiento de servicios propios. OpenFreeMap autoriza descargas móviles limitadas (hasta 3.000 teselas o 50 MB, cuatro peticiones simultáneas y User-Agent propio); la app conserva límites internos inferiores. No es una descarga ilimitada de un país. La aplicación no consume teselas públicas de tile.openstreetmap.org ni extrae comercios de Google Maps.

Los pins compartidos de Google Maps siguen siendo una entrada para elegir el centro de una zona propia. Eso no importa su mapa base, comercios ni ubicación en vivo de WhatsApp.
