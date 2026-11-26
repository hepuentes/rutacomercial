# Alcance de la APK 0.4.0

La aplicación funciona con información local real. No contiene registros de demostración. «Implementado» describe código funcional; la evidencia de ejecución y las comprobaciones todavía pendientes se detallan por separado en `PRUEBAS.md`.

## Cambios 0.4.0

- Mi ubicación e iniciar/reanudar verifican por separado permisos y el interruptor de ubicación de Android. Resolución nativa de Google Play o ajustes de Android; reintento al volver. No se pide GPS al abrir la app.
- Consulta puntual con ubicación combinada, caché reciente, alternativa Android y plazo de 20 s. Muestra posición aproximada mientras mejora. Círculo de precisión y punto gris cuando la lectura supera 30 s. Mi ubicación también sale del selector de zona y recentra.
- Entrega a un toque: preparar material una vez, una tarjeta por toque, deshacer con movimiento compensatorio, ubicación opcional de hasta 30 s/100 m, campaña/zona/jornada y asociación posterior con negocio existente. No crea negocios ficticios. Resúmenes, CSV y respaldo incluyen estas entregas sin duplicar inventario.
- Zonas con identidad de color, borde más grueso para la seleccionada y relleno suave. Migración de los colores predeterminados de zonas antiguas; estado separado en el encabezado.
- Room v2 agrega quick_deliveries. Respaldo schema2 con lectura de schema1. No se necesita borrar datos ni caché.

## Correcciones 0.3.1 (históricas)

- Abrir la app no solicita GPS. Mi ubicación utiliza una lectura reciente válida si existe y, en caso contrario, espera una lectura nueva.
- El punto azul se actualiza con lecturas fiables aunque el filtro no confirme distancia; el trazo y las visitas siguen siendo registros separados.
- Durante la jornada la cámara sigue la ubicación. Explorar manualmente o elegir/editar una zona suspende el centrado; Mi ubicación lo retoma. El historial conserva su encuadre propio.
- En 0.3.1 la entrega rápida era una propuesta; se implementa en 0.4.0. La conexión de ubicación en vivo de WhatsApp sigue pendiente.

## Cambios 0.3.0

- Compartir un pin de Google Maps con RutaComercial, revisarlo y delimitar una zona alrededor. Entrada alternativa de enlace/coordenadas en Opciones. No inicia GPS ni guarda zona automáticamente; los enlaces sin coordenadas explícitas muestran ayuda para compartir un pin.
- Fused Location Provider cada 6 segundos y alternativa de GPS Android cuando Google Play no está disponible o falla el registro. El ahorro físico no está medido.
- Lecturas imprecisas o saltos aislados se descartan sin reiniciar el filtro; más de 30 segundos sin señal fiable separan segmentos. Una parada con lecturas fiables no genera un falso corte por falta de puntos guardados. Los recorridos existentes no se recalculan.
- Aviso al seleccionar más de 1 km por lado para facilitar trabajo por cuadras y descarga offline.

## Cambios 0.2.0 conservados

- Inicio directo en un mapa amplio sin desplazamiento de página; controles fijos y opciones en un panel inferior. Se mantienen el panel de resultados y las funciones comerciales.
- Elegir zona con rectángulo, cuatro esquinas ajustables y zoom; nombre y código automáticos, sin ciudad obligatoria. Selección persistida y renombrado posterior. Estados manuales y colores por zona y recorridos de hoy/anteriores hasta 30 días.
- Descarga del rectángulo que contiene el sector elegido, hasta 1 km por lado. Dimensiones y límite de almacenamiento informados, progreso, pausa y reanudación. Google Maps externo para consultar la zona; no requiere claves ni factura de Google y no importa su cartografía ni comercios.
- Filtro peatonal con estabilización y confirmación temporal, radio de quietud según incertidumbre y segmentos separados ante señal deficiente. No reconstruye ni reescribe recorridos antiguos. No garantiza exactitud en interiores.
- Negocios con campos opcionales plegados, ubicación prellenada desde el punto elegido y sugerencia de sector cuando contiene ese punto. La selección manual de coordenadas continúa disponible.

| Área | Implementado | Límites y ampliaciones |
|---|---|---|
| Jornadas y GPS | Inicio, pausa, reanudación y cierre; foreground y notificación; distancia, tiempos, incidencias y recorridos persistidos; precisión, saltos y segmentos separados | Continuidad y batería dependen de Android y del teléfono. La recuperación de proceso queda en pausa; no hay seguimiento fuera de una jornada |
| Mapa | MapLibre/OpenFreeMap, ubicación, negocios, polígonos, trazos históricos y fallback sin cartografía | Servicio público sin SLA. El mapa no es un inventario exhaustivo de negocios |
| Zonas y sectores | Ciudades, localidades, barrios; polígonos con nombre/código/color/estado; revisiones y estadísticas | Cobertura por sectores con visitas y negocios conocidos, no porcentaje de superficie ni censo comercial |
| Establecimientos | Nombre, categoría, ubicación, dirección, barrio/sector, teléfono, foto, notas, prioridad, estado, seguimiento e historial; búsqueda/filtros | Las fotos se eligen del dispositivo; no hay cámara integrada. Los formularios sin guardar pueden perderse si el proceso termina |
| Duplicados | Distancia y parecido del nombre; aviso, guardar separado o fusión confirmada, manteniendo historia | Se necesita decisión humana; no se importan automáticamente negocios de OSM |
| Visitas | Nueve estados requeridos, campaña, cantidad, comentarios, foto opcional, posición reciente opcional; revisión humana auditada | Son visitas/entregas declaradas. El GPS no las verifica ni las convierte automáticamente en entregas confirmadas |
| Campañas | Nombre, descripción, inicio/fin opcional, costo unitario opcional; stock inicial y movimientos | Costos del panel corresponden al material; no incluye remuneraciones o transporte |
| Inventario | Depósito y material en mano; entradas, asignación, entregas de visitas o a un toque, anulaciones auditadas, dañadas y devoluciones; transacciones/idempotencia | Saldo en mano del propietario, sin cuentas de empleados. No hay correcciones que borren el libro histórico |
| Planificación | Lista persistida de establecimientos, orden manual, filtros/prioridad y cercanía en línea recta | Sin optimización ni indicaciones de ruta peatonal; se informa claramente la naturaleza de la distancia |
| Resultados | Jornadas, km, tiempos, visitas, tarjetas, entregas sin identificar, pendientes, actividad por sector y costos registrados | Períodos hoy/7 días/30 días/todo; filtros campaña/sector. Distancia/tiempo filtrados usan jornadas completas con visitas coincidentes. Fechas personalizadas disponibles en historial |
| Sin internet | Persistencia de recorridos, visitas, campañas y movimientos; regiones pequeñas descargables con límites autorizados | La región debe estar completa. Fuera del área o del zoom descargado puede faltar mapa. No se descargan mapas de los servidores públicos de teselas OSM |
| Informes | CSV comercial y de inventario, GPX y GeoJSON con segmentos separados | Los CSV incluyen todos los datos comerciales, independientemente del filtro visual. Exportaciones sin cifrar |
| Respaldo | Archivo portable cifrado/autenticado, fotografías, validación de integridad y restauración transaccional | Solo destino vacío; sin fusión de bases ni recuperación de contraseña. 32 MB de contenido y 20 MB de fotografías; no incluye teselas |
| Privacidad | Datos privados de Android, sin copia automática a nube, permisos mínimos, parada visible, retención manual configurable de GPS | No hay cifrado SQLCipher adicional ni autenticación de múltiples usuarios. La APK debug es para pruebas. Fotos canceladas o reemplazadas pueden dejar copias privadas no referenciadas; no se exportan en el respaldo |

## Etapas posteriores, no implementadas

Servidor Flask/PostGIS, cuentas individuales, roles, asignación entre empleados, sincronización con WorkManager, supervisión remota, control centralizado, alertas antifraude avanzadas, QR y atribución de contactos, importaciones autorizadas de comercios y optimización peatonal. Están documentados contratos y precauciones, pero no se presentan como capacidades existentes.

## Hitos

Los hitos de estructura/GPS, gestión comercial e informes/respaldo tienen implementación local. La entrega APK y las pruebas verificables se registran en `PRUEBAS.md` y `dist/`. La validación de campo en teléfono físico sigue separada de las pruebas automatizadas y del emulador.
