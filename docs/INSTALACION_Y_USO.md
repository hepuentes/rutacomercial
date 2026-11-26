# Instalación y primeros pasos

## Instalar la APK de pruebas en tu teléfono

1. Necesitás Android 8.0 o posterior. La validación realizada en emulador se detalla en `PRUEBAS.md`; se debe completar una caminata real antes de usarla en una jornada importante.
2. En Windows, abrí `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android\dist` y buscá `RutaComercial-0.5.0-debug.apk`.
3. Conectá el teléfono por USB y copiá ese archivo a **Descargas**. También podés usar un medio privado que controles.
4. En el teléfono, abrí **Archivos → Descargas** y tocá la APK. Si Android lo solicita, permití temporalmente que esa aplicación de archivos instale aplicaciones de esta fuente.
5. Tocá **Instalar** y luego **Abrir**. Podés desactivar nuevamente el permiso de instalación de esa fuente.
6. No desinstales una versión con registros sin crear antes un respaldo cifrado. Una actualización firmada con la misma clave de pruebas puede instalarse encima; desinstalar elimina la base local.

Es una APK **de depuración**, firmada con una clave local de pruebas. No es una versión de producción ni una publicación en Play Store. No necesitás una cuenta, contraseña de servidor, tarjeta bancaria ni servicios de pago.

## Repartir a pie con el teléfono guardado (0.5.0)

Para recorrer libremente, tocá **Empezar recorrido**. Para trabajar en una zona, encuadrá las cuadras y elegí **Elegir zona → Trazar a mano**. Si ya tenés zonas, elegí primero **Crear zona en el mapa**. Dibujá una vuelta con un dedo, soltá para cerrar y tocá **Usar esta zona**. Otro trazo reemplaza el borrador. **Usar rectángulo** permite volver a mover o acercar el mapa. La jornada usa como referencia la versión de la zona elegida al empezar.

Tocá **Mi ubicación** para ubicarte. El botón derecho permite orientar el mapa una vez hacia el rumbo del teléfono, seguir sus giros o volver al norte. El abanico muestra hacia dónde apunta el teléfono. Su amplitud representa la calidad del rumbo que informa el sensor. El círculo de precisión representa el margen de posición. Los sensores de rumbo se liberan al salir del mapa o apagar la pantalla. El servicio de recorrido sigue las reglas de la jornada.

En **Más → Historial** consultá la distancia, duración, velocidad media, paradas observadas y episodios rápidos. La app cuenta una permanencia con lecturas de hasta 15 m de error durante al menos 30 s dentro de un radio de 15 m. Una nueva parada requiere salir de ese radio o un nuevo intervalo de observación. Una ventana de al menos 30 s con desplazamiento sostenido superior a 12 km/h genera un episodio para revisar. Las pérdidas de señal interrumpen estas ventanas. Una entrega breve puede quedar fuera del conteo y un semáforo puede figurar como parada.

La cobertura cuenta celdas de 50 m cuyo centro está dentro del polígono y que contienen al menos un punto GPS aceptado. Volver por una celda cuenta una sola vez. La métrica describe superficie muestreada. Identificar porcentajes de calles o veredas requeriría un inventario vial de referencia. En modo libre podés consultar el trazo y el ritmo. Los recorridos de versiones anteriores conservan sus métricas originales y admiten el cálculo de la media.

## Entregar una tarjeta con un toque (opcional)

1. En el mapa, entrá en **Opciones → Registrar tarjetas** y tocá **Preparar entrega a un toque → Agregar material nuevo**. Indicá el nombre y cuántas tarjetas llevás. Esto crea el material y lo asigna para repartir. Si ya existe una campaña, elegila y asigná tarjetas del depósito desde el mismo panel.
2. Cada entrega se registra con **Entregué 1 tarjeta**. Quedan guardadas la hora, campaña, zona seleccionada, jornada si existe y ubicación reciente si está disponible. Sin GPS se conserva la entrega sin inventar coordenadas.
3. **Deshacer** anula la última entrega del día y reintegra la tarjeta. El historial conserva la anulación.
4. Tocá el nombre del material para cambiarlo o revisar las últimas entregas. Podés asociar una a un negocio ya registrado; no se descuenta otra tarjeta.
5. Los puntos rosados representan entregas declaradas, no negocios comprobados. La posición puede tener error; varios locales contiguos pueden compartir casi el mismo punto.

## Primera campaña y entrega detallada

La versión 0.2.0 abre directamente el mapa. No requiere registro ni configuración de ciudades, campañas o sectores para empezar a caminar. Podés entrar en **Inicio** para ver el resumen de actividad.

1. Entrá en **Más → Campañas** y creá una campaña con nombre, fechas, cantidad inicial y costo unitario si lo conocés.
2. Abrí sus movimientos y elegí **Asignar para repartir**. Las tarjetas pasan del depósito a las tarjetas en mano. No se puede entregar más de lo que tenés asignado.
3. En **Mapa**, tocá **Mi ubicación** y concedé ubicación precisa. También podés desplazarte por el mapa y tocar el punto donde está el negocio.
4. Elegí **Agregar negocio**. El punto elegido completa la ubicación y, si corresponde, el sector. Nombre, categoría y referencia están a mano; teléfono, fotografía y otros campos quedan en **Más datos (opcionales)**. Revisá cualquier aviso de posible duplicado.
5. Abrí la ficha del negocio, registrá una visita, elegí el estado, la campaña y la cantidad. Podés agregar comentarios, una fotografía y fecha de seguimiento.
6. Las tarjetas se descuentan en la misma operación que guarda la visita. El panel muestra datos reales de este teléfono; no se cargan datos de demostración.

## Hacer un recorrido

Abrir la app permite consultar el mapa sin GPS. **Mi ubicación** pide permiso y, si la ubicación del teléfono está apagada, ofrece activarla mediante el diálogo nativo o los ajustes de Android. Al volver reintenta automáticamente. Muestra una lectura reciente cuando hay una disponible y busca mejorarla durante un máximo de 20 segundos; no garantiza respuesta instantánea en interiores.

El punto azul muestra la última lectura reciente y el círculo su precisión estimada; pasa a gris cuando supera 30 segundos. Durante una jornada la cámara sigue el punto sin cambiar el zoom. Explorar el mapa suspende ese centrado; **Mi ubicación** lo retoma y sale de una selección de zona en curso. Fuera de una jornada la consulta termina sola y no guarda recorrido.

1. Activá **Ubicación** en Android. En el mapa podés usar **Mi ubicación**, elegir una zona o tocar directamente **Empezar recorrido**.
2. Concedé **ubicación precisa mientras se usa la app** y **notificaciones**. Son necesarias para iniciar un recorrido con aviso visible. No se pide acceso permanente a ubicación fuera de las jornadas.
3. Esperá las primeras lecturas estables, preferentemente al aire libre. Se confirma el desplazamiento con varias posiciones antes de sumar distancia. Los movimientos muy cortos pueden quedar descartados: no sirve para medir un recorrido entre habitaciones. Los trazos antiguos se conservan sin recalcular.
4. **Pausar** detiene el GPS; **Reanudar** inicia un segmento nuevo. **Terminar** guarda la jornada y detiene el servicio. La notificación permite pausar o finalizar sin buscar la pantalla.
5. La app intenta registrar con la pantalla apagada mediante un servicio foreground. Si el fabricante restringe la batería, revisá la configuración de batería de RutaComercial. No se debe afirmar continuidad hasta comprobarla en tu teléfono.
6. Si Android cierra el proceso, al abrir nuevamente la app la jornada se recupera en pausa. Reanudala expresamente cuando quieras continuar. No se dibujan tramos que faltaron.
7. Consultá la jornada terminada en **Más → Historial**. Podés ver su mapa y exportar GPX o GeoJSON.

## Territorio y planificación

En **Mapa → Elegir zona**, la vista se acerca a escala de pocas cuadras. Mové el mapa o ajustá el zoom y arrastrá las cuatro esquinas del rectángulo. Tocá **Usar esta zona**: se propone automáticamente un nombre como *Zona 1*, sin pedir ciudad ni código. El guardado espera a que termine de moverse la cámara. Si ya hay sectores, podés elegir uno o crear otro. La zona elegida se conserva al cerrar la app. En **Opciones → Cambiar nombre de la zona** podés renombrarla después.

En **Opciones**, el estado de la zona se cambia manualmente: pendiente (naranja), en curso (azul) o completada (verde). Se muestra la actividad de negocios registrados. Los recorridos de hoy aparecen por defecto; **Mostrar recorridos anteriores (30 días)** añade trazos grises. El historial completo sigue disponible en **Más → Historial**. Caminar una calle no registra visitas automáticamente.

**Opciones → Consultar zona en Google Maps** abre Google Maps o el navegador en el centro de la zona. Allí buscá un lugar o mantené pulsado para poner un pin y elegí **Compartir → RutaComercial → Delimitar zona aquí**. Revisá el punto en el mapa, ajustá el recuadro y tocá **Usar esta zona**. También podés pegar el enlace o las coordenadas en **Opciones → Traer ubicación de Google Maps**. Algunos enlaces no contienen coordenadas recuperables; la app pedirá compartir un pin. Los enlaces cortos requieren internet. No requiere clave ni facturación de Maps; no importa negocios, polígonos ni mapas descargados por Google. El mapa interno sigue siendo OpenFreeMap.

Para caminar por pocas cuadras, acercá el mapa antes de guardar. Una selección como 2.674 × 3.485 m cubre varios kilómetros y supera el límite de descarga: se puede guardar como zona de trabajo, pero conviene dividirla en sectores más pequeños. La app avisa cuando se supera 1 km por lado.

## Comprobar GPS y batería en el POCO X6 Pro

En 0.3.0 se solicita ubicación cada 6 segundos y se usa el proveedor combinado de Google Play si está disponible. Una lectura imprecisa aislada se descarta sin reiniciar el recorrido. **GPS activo · sin desplazamiento confirmado** significa que llegan lecturas fiables pero no hay movimiento suficiente; **Sin ubicación precisa reciente** señala más de 30 segundos sin una lectura fiable. Una interrupción real sigue separando los trazos.

Para comparar, anotá porcentaje de carga al inicio y al terminar una caminata de 30–60 minutos, manteniendo los mismos ajustes de batería y la pantalla apagada la mayor parte del tiempo. Pausá desde la notificación durante descansos largos y terminá la jornada al acabar. El ahorro real y la continuidad con las restricciones de Xiaomi necesitan esta prueba física; el emulador no los mide. Esta actualización no cambia los ajustes de ahorro del teléfono.

En **Más → Sectores** podés crear ciudades/localidades/barrios y sectores. Para delimitar un sector, tocá al menos tres vértices en el mapa; asigná nombre, código y color. Editar límites conserva revisiones anteriores. Completá el estado del sector con criterio comercial: recorrer una calle no significa visitar todos sus negocios.

En **Negocios** buscá por nombre, categoría o ubicación y filtrá por estado/sector. Agregá paradas al **Recorrido planificado** y cambiá su orden. Las distancias indicadas son en línea recta; no son instrucciones peatonales ni rutas optimizadas.

## Salir sin internet

1. Con internet disponible y una zona elegida, entrá en **Mapa → Opciones → Guardar mapa sin internet**.
2. Se propone el nombre y se muestran las dimensiones. La descarga abarca el rectángulo del sector, hasta **1 km de ancho y 1 km de alto**. Una zona mayor debe dividirse para descargarla. Sin sector, se ofrece 1 km × 1 km alrededor del punto elegido o centro del mapa.
3. Tocá **Guardar mapa sin internet** y esperá **Disponible sin conexión**. El tamaño depende del área; se pausa si alcanza 40 MB almacenados. Una descarga incompleta no está lista. Podés pausar, reanudar o eliminar los mapas desde este panel o desde **Más → Mapas sin conexión**.
4. El detalle descargado corresponde a niveles de zoom 12 a 16. Si te alejás del área o cambiás mucho el zoom puede faltar cartografía.
5. Aunque no haya mapa, los recorridos GPS, visitas e inventario siguen guardándose localmente. No borres una región necesaria antes de salir.

## Respaldo, informes y conservación

En **Más → Configuración**, elegí **Crear respaldo cifrado**, escribí y repetí una contraseña de al menos 12 caracteres y seleccioná dónde guardar el archivo `.rcb`. Incluye datos y fotografías adjuntas, pero no mapas. Conservá otra copia del archivo y su contraseña por separado. La contraseña no puede recuperarse desde la app.

**Restaurar respaldo** exige una instalación sin registros. Si la app ya tiene datos, rechaza la restauración sin borrarlos. Para cambiar de teléfono, instalá la APK en el nuevo, copiá el respaldo y restauralo allí antes de registrar nada. No borres el teléfono anterior hasta verificar el nuevo. No se combinan bases automáticamente.

Los CSV se abren en Excel: contienen UTF-8 con marca BOM y separador `;`. Si tu configuración de Excel no lo detecta, elegí **Datos → Desde texto/CSV**, codificación **UTF-8** y delimitador **punto y coma**. Los CSV, GPX y GeoJSON no están cifrados.

La conservación configurable borra, solo tras confirmación, puntos GPS de jornadas finalizadas anteriores al plazo seleccionado. Mantiene las cifras agregadas de esas jornadas y los registros comerciales. Hacé un respaldo antes de borrar.

## Abrir el código o compilar nuevamente

Carpeta completa: `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android`.

Para Android Studio, elegí **Open** y esa carpeta. El proyecto usa AGP 9.3.3; la instalación detectada de Android Studio 2025.2.2 es anterior a ese plugin. Para trabajar desde el IDE se requiere una versión estable compatible con AGP 9.3 o superior. No se actualizó tu Android Studio ni su configuración global. La compilación verificada se hace por línea de comandos con JBR 21 y Gradle Wrapper.

En PowerShell:

```powershell
Set-Location -LiteralPath 'C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android'
.\scripts\build.ps1
```

El script usa las herramientas/cachés locales en `.tools`, `.gradle` y `.android`. Necesita internet la primera vez para resolver bibliotecas. La APK generada por Gradle queda en `C:\Users\Admin\Documents\ChatGPT\RutaComercial_Android\app\build\outputs\apk\debug\app-debug.apk`. No hay claves de firma de producción.
