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

En Owner, **Ver repartidores hoy** (en Asignaciones y en Opciones del mapa) muestra a cada repartidor que envió avances hoy: su trazo, su última posición y su estado (recorriendo, en pausa, sin señal si no envía hace más de 5 minutos, o terminó). Se actualiza con cada avance, aproximadamente cada minuto.

Avisos: el Repartidor recibe "Tenés una tarea nueva"; el Owner recibe propuestas de zona, repartidores vinculados y recorridos terminados. Llegan mientras la app está abierta o en segundo plano (siempre durante un recorrido). Con la app cerrada del todo no llegan: eso requeriría Cloud Functions, que necesita el plan pago de Firebase.
