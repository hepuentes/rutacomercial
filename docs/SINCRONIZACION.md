# Sincronización de asignaciones

Owner y Repartidor usan el mismo proyecto de Firebase, con autenticación anónima y Cloud Firestore Standard. Cada instalación tiene un identificador propio. Borrar los datos de la app o desinstalarla cambia esa identidad.

## Vincular los teléfonos

1. En Repartidor, tocá **Copiar mi código de teléfono**. Si ya hay una tarea abierta, el botón está en **Opciones**.
2. En las asignaciones de Owner, elegí la zona, escribí el nombre del repartidor y pegá ese código. Tocá **Asignar en línea**. El código queda asociado al nombre en ese teléfono.
3. En Repartidor, abrí la tarea recibida y confirmá el primer vínculo con el QR o PIN del Owner. Las tareas posteriores del mismo Owner se pueden aceptar directamente.

En Repartidor, **Enviar al Owner** muestra el código y abre el menú para mandarlo por WhatsApp. En Owner se puede pegar el mensaje completo: la app extrae el código.

## Propuestas de zona

Cuando el Repartidor ya aceptó al menos una tarea en línea, **Proponer zona → Enviar al Owner** sube la propuesta a Firestore (`proposals/{id}`), sin archivos. En Owner aparece en **Asignaciones → Propuestas de zona por revisar**. **Aceptar y enviar tarea** guarda la zona y devuelve la tarea en línea al mismo teléfono. Si el Repartidor todavía no está vinculado, la propuesta se comparte como archivo, igual que antes.

Una tarea que ya existe en Owner se puede enviar desde su detalle. Si se envió a un teléfono y después se cambia de repartidor, creá otra asignación para conservar la trazabilidad.

El seguimiento se registra en la base local. Durante una jornada activa se intenta publicar el avance aproximadamente cada minuto. Al pausar o terminar se programa una entrega que puede ejecutarse cuando Android vuelva a disponer de conexión. Owner incorpora el informe a las mismas vistas de mapa e historial que usa la importación por archivo. Cada jornada tiene un identificador estable para reemplazar el avance al llegar el cierre.

## Configurar un despliegue

En el proyecto gratuito, activar Authentication con el proveedor Anónimo y crear la base de Firestore Standard con el nombre predeterminado. Publicar las reglas del repositorio con `firebase deploy --only firestore:rules --project ID`.

Registrar las aplicaciones Android `com.rutacomercial.app` y `com.rutacomercial.app.repartidor`. Descargar la configuración cliente de cada una a `app/src/owner/google-services.json` y `app/src/repartidor/google-services.json`. También se puede indicar otra carpeta de configuraciones con la propiedad Gradle `firebaseConfigRoot`. Cada subcarpeta lleva el nombre de la edición. Ejecutar `scripts/build.ps1` para compilar y verificar. Una compilación sin configuración conserva el intercambio por archivo.

## Verificar las reglas localmente

Desde la raíz, ejecutar `firebase emulators:exec --only auth,firestore --project demo-rutacomercial "python scripts/firebase_smoke.py"`. La prueba crea identidades temporales, envía una tarea y dos estados de un recorrido, y comprueba la denegación del acceso a un tercero.
