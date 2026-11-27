# Sincronización de asignaciones

Owner y Repartidor usan el mismo proyecto de Firebase, con autenticación anónima y Cloud Firestore Standard. Cada instalación tiene un identificador propio. Borrar los datos de la app o desinstalarla cambia esa identidad.

## Vincular los teléfonos

Se hace una sola vez por teléfono de repartidor:

1. En Owner, entrá a **Asignaciones** y tocá **Vincular repartidor**. Aparece un QR y un código de 6 caracteres que vale 30 minutos.
2. En Repartidor, tocá **Escanear QR de mi Owner**, o **Tengo un código** si no están juntos, y escribí tu nombre. También está en **Opciones** cuando ya hay una tarea abierta.
3. El repartidor aparece en **Repartidores vinculados**. Al tocar su nombre, **Asignar en línea** le envía la tarea, sin códigos ni PIN.

Al vincularse, el Repartidor sube solo las tareas que ya tenía de ese Owner, también las recibidas por archivo, y con ellas todos sus recorridos. No hace falta reenviar nada. El código se guarda en `invites/{código}` y solo se puede leer conociéndolo.

El QR lleva la cuenta del Owner y su clave pública. Con eso el Repartidor confía en las tareas firmadas por ese Owner y publica su código y su nombre en `links/{uid}`.

Sin cámara, el Repartidor puede tocar **Sin cámara: enviar mi código por WhatsApp**. El Owner pega el mensaje completo en **Código del teléfono** y la primera tarea se confirma con el QR o el PIN de la tarea, como antes.

Una tarea que ya existe en Owner se puede enviar desde su detalle. Si se envió a un teléfono y después se cambia de repartidor, creá otra asignación para conservar la trazabilidad.

El seguimiento se registra en la base local. Durante una jornada activa se intenta publicar el avance aproximadamente cada minuto. Al pausar o terminar se programa una entrega que puede ejecutarse cuando Android vuelva a disponer de conexión. Owner incorpora el informe a las mismas vistas de mapa e historial que usa la importación por archivo. Cada jornada tiene un identificador estable para reemplazar el avance al llegar el cierre.

## Cuenta de Owner con Google

En **Asignaciones → Conectar con Google**, el Owner asocia su identidad a una cuenta de Google (gratis con Firebase Authentication). La cuenta anónima se vincula sin cambiar de identidad. Si el Owner reinstala o cambia de teléfono, al conectar la misma cuenta recupera su identidad y la lista de repartidores vinculados. Los repartidores aceptan la nueva clave de firma porque llega desde la misma cuenta de Owner. Las zonas y recorridos guardados en el teléfono no viajan con la cuenta: para eso está el respaldo de la app.

Requiere el proveedor Google activado en Firebase, la huella SHA-1 de la clave de firma registrada en la app Owner y el `google-services.json` descargado después de activarlo. Sin el cliente web en esa configuración, el botón no aparece.

## Propuestas de zona

Cuando el Repartidor está vinculado (por QR o porque ya aceptó una tarea en línea), **Proponer zona → Enviar al Owner** sube la propuesta a Firestore (`proposals/{id}`), sin archivos. En Owner aparece en **Asignaciones → Propuestas de zona por revisar**. **Aceptar y enviar tarea** guarda la zona y devuelve la tarea en línea al mismo teléfono. Si el Repartidor todavía no está vinculado, la propuesta se comparte como archivo, igual que antes.

## Configurar un despliegue

En el proyecto gratuito, activar Authentication con el proveedor Anónimo y crear la base de Firestore Standard con el nombre predeterminado. Publicar las reglas del repositorio con `firebase deploy --only firestore:rules --project ID`.

Registrar las aplicaciones Android `com.rutacomercial.app` y `com.rutacomercial.app.repartidor`. Descargar la configuración cliente de cada una a `app/src/owner/google-services.json` y `app/src/repartidor/google-services.json`. También se puede indicar otra carpeta de configuraciones con la propiedad Gradle `firebaseConfigRoot`. Cada subcarpeta lleva el nombre de la edición. Ejecutar `scripts/build.ps1` para compilar y verificar. Una compilación sin configuración conserva el intercambio por archivo.

## Verificar las reglas localmente

Desde la raíz, ejecutar `firebase emulators:exec --only auth,firestore --project demo-rutacomercial "python scripts/firebase_smoke.py"`. La prueba crea identidades temporales, envía una tarea y dos estados de un recorrido, y comprueba la denegación del acceso a un tercero.

## Hoy y avisos

En Owner, **Ver repartidores hoy** (en Asignaciones y en Opciones del mapa) muestra a cada repartidor que envió avances hoy: su trazo, su última posición y su estado (recorriendo, en pausa, sin señal si no envía hace más de 5 minutos, o terminó). Se actualiza con cada avance: cada minuto, o cada 20 segundos mientras el Owner tiene abierto Hoy o el mapa de esa tarea (el Owner deja una marca `watch/{repartidor}` que vence a los 4 minutos). Tocá un repartidor para ver sus marcas de inicio, pausas, reanudaciones y fin. El mapa de cada tarea muestra el punto en vivo del repartidor con hace cuánto llegó, y **Ver cubiertas** suma en verde las zonas ya terminadas de la campaña actual.

### Campañas

En Asignaciones, **Cerrar campaña** guarda las tareas terminadas con sus recorridos en **Campañas anteriores** y las saca del mapa, del estado de las zonas y de la guía. Las tareas en curso siguen abiertas y los pagos pendientes no se pierden. Con **Repetir zonas** esas zonas quedan abiertas para asignarlas de nuevo al mismo u otro repartidor. Las campañas se guardan en `assignments/campaigns.json` y viajan en el respaldo.

### Avisos

Tarea nueva, propuesta recibida o aceptada, repartidor fuera de zona y recorrido terminado salen como aviso emergente. Los avisos de una misma tarea se reemplazan en vez de acumularse. El resumen de las 20 h es un aviso normal. Sin Firebase Cloud Messaging (plan pago) los avisos llegan mientras la app está viva: siempre durante un recorrido; en el Owner, Android puede congelar la app en segundo plano y el aviso llega al volver a abrirla.

Avisos: el Repartidor recibe "Tenés una tarea nueva"; el Owner recibe propuestas de zona, repartidores vinculados y recorridos terminados. Llegan mientras la app está abierta o en segundo plano (siempre durante un recorrido). Con la app cerrada del todo no llegan: eso requeriría Cloud Functions, que necesita el plan pago de Firebase.

## Seguridad

- Un repartidor solo se vincula con una invitación vigente (30 minutos) que el Owner creó; el QR lleva el mismo código. Conocer la cuenta de un Owner no alcanza.
- Solo un repartidor vinculado puede proponer zonas o subir tareas, y únicamente hacia su Owner.
- Todo lo que se sube tiene campos fijos y tamaño limitado (partes de 600 KB, 11 como máximo).
- Probado contra el proyecto real: vínculo sin invitación, con invitación falsa, propuesta y tarea sin vínculo, bloques de 700 KB, 50 partes y campos extra son rechazados.
- Pendiente fuera de la app: restringir la clave de API de Android al paquete y la huella SHA-1 en Google Cloud, y activar App Check.

## Calles de la Guía

Al asignar en línea, el Owner descarga las calles de la zona y las sube junto a la tarea (`tasks/{id}/guide/streets`). El Repartidor las guarda al recibir la tarea, así la Guía no depende de los servidores públicos ni de la señal.

## Resumen del día

El Owner recibe un aviso alrededor de las 20 h con km, horas y zonas por repartidor. En **Hoy**, **Compartir** manda ese resumen por WhatsApp.

## Tarifa por hora

En **Asignaciones**, el Owner escribe una vez el **valor de la hora**. Cada tarea guarda el valor vigente al asignarla (también al aceptar una propuesta o reasignar), así que cambiarlo después no altera tareas anteriores; dentro de la tarea se puede cambiar.

El pago usa solo el **tiempo activo**, sin pausas, sumando todas las jornadas de la tarea: "0 h 12 min activos × $ 4.000 por hora = $ 851". Mientras la tarea no terminó dice "a la fecha". Cada tarea tiene **Pagado / Pendiente de pago** con la fecha, y Asignaciones muestra **Por pagar** con el total por repartidor y "Marcar todo como pagado". Los valores y marcas viajan en el respaldo cifrado. Solo los ve el Owner.

## Medidas contra el fraude

Sin permisos nuevos, cámara ni micrófono: todo sale del GPS de la jornada, y el Repartidor ve lo mismo que el Owner.

- **Vehículo:** más de 15 km/h sostenidos un minuto (moto, auto, app de viajes, colectivo) es un tramo en vehículo. No suma km, cobertura ni tiempo pagable; los puntos registrados antes de confirmarlo se descartan. Inicio y fin se ajustan al primer y último paso rápido para no descontar la caminata de alrededor. Si dura más de 5 minutos, el recorrido se pausa solo con fecha en el inicio del tramo.
- **GPS falso:** las ubicaciones que Android marca como simuladas no se registran y ese tiempo no se paga.
- **Reloj:** el reloj del teléfono se compara cada 10 s con el reloj interno, que no se puede cambiar. Un adelanto no se paga; si después se corrige, se compensa. Al volver la hora atrás se corrige la marca de actividad para que una pausa posterior no conserve la hora falsa.
- **Fuera de zona sin elegir:** el tiempo de las salidas que generaron aviso sin que el repartidor eligiera "Seguir fuera" se informa al Owner. No se descuenta.
- **Transparencia:** la primera vez que inicia un recorrido, el Repartidor ve "Cómo se cuenta tu tiempo" (también en Opciones). En su panel ve "No cuentan: … · Pagable …". El Owner ve el mismo detalle en la tarjeta de Pago.

Límite conocido: si el reloj se adelanta, se pausa con la hora adelantada y se vuelve atrás estando en pausa, ese tiempo queda descontado y el repartidor puede perder minutos hasta que la hora real alcance a la adelantada. Solo ocurre si se manipula la hora.
