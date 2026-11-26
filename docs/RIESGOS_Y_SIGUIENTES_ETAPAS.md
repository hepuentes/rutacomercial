# Riesgos, límites y siguientes etapas

## Límites que deben entenderse

- Una ruta GPS no prueba visitas o entregas. Las fotos, posiciones simuladas, tiempos o QR tampoco garantizan la entrega. La revisión humana debe considerar varias evidencias y permitir explicación.
- Precisión, continuidad y consumo dependen del teléfono, permisos, cobertura de satélites y políticas de batería del fabricante. El servicio foreground ayuda, pero no evita un «Forzar detención», revocación de permisos o apagado. No se reconstruye lo que no fue registrado.
- El mapa utiliza un servicio público sin SLA. Una región solo se considera disponible si terminó de descargarse; fuera de ella o de sus niveles de zoom puede faltar cartografía. El registro local sigue funcionando. No hacer descargas masivas ni aumentar límites sin nueva autorización del proveedor.
- La cobertura mostrada se refiere a los establecimientos registrados y/o sectores con actividad. No representa todos los negocios de una ciudad. Las distancias de planificación son en línea recta, no rutas peatonales.
- La base no tiene cifrado de aplicación adicional. Usar bloqueo de pantalla y cifrado del dispositivo. Esta APK de depuración no es una publicación de producción. No guardar secretos ni claves de firma en el repositorio.
- La restauración funciona sobre una instalación sin registros. No hay combinación automática entre dos bases con datos, ni recuperación de contraseña. Conservar el archivo y la contraseña por separado. Los mapas descargados no forman parte del respaldo.
- Las exportaciones contienen ubicaciones e información comercial sin cifrar. Compartirlas únicamente con destinatarios autorizados. Evitar fotografías de caras, documentos o datos innecesarios.
- La observación de estado carga las tablas locales para el panel. Antes de operar con cientos de miles de puntos se deben agregar consultas agregadas/paginadas y perfilar memoria; la retención permite reducir coordenadas antiguas conservando estadísticas.

## Validación física pendiente

Realizar una caminata con pantalla encendida y apagada en los modelos Android que se usarán. Medir batería durante al menos una jornada, verificar pérdida de GPS en interiores, permisos revocados, ahorro de batería, modo avión, cierre inesperado y reinicio del teléfono. Las pruebas en emulador no sustituyen esta validación ni prueban posiciones GPS de una caminata real.

## Orden recomendado

1. Piloto con el propietario; validar exactitud y flujo comercial, revisar tamaño de datos y respaldos, ajustar filtros según evidencia de campo.
2. APK de distribución firmada, gestión segura de claves fuera del repositorio, política de actualizaciones y revisión independiente de seguridad y accesibilidad.
3. VPS y PostgreSQL/PostGIS, Flask, TLS, respaldos del servidor, monitoreo, retención y recuperación ante fallos. El costo se determinará al seleccionar capacidad; no se contrató infraestructura.
4. Usuarios y roles, asignaciones y sincronización idempotente con conflictos. Probar desconexión prolongada y conciliación de stock entre dispositivos antes de supervisión remota.
5. Panel de supervisión y alertas revisables; campañas QR con contactos atribuibles separados de entregas. Motor peatonal como Valhalla/OSRM con infraestructura y licencias verificadas; rutas de acceso legal, cierres y restricciones.

## Revisión de privacidad y relaciones laborales

Antes de incorporar trabajadores, encargar una revisión de las obligaciones aplicables en el país de operación: finalidad y base jurídica, información al trabajador, proporcionalidad del seguimiento, horarios, plazos de conservación, derechos de acceso/corrección/eliminación, transferencias internacionales, acceso a evidencias y procedimiento de revisión humana. No se implementó una política legal universal ni se afirma que la app por sí sola garantice cumplimiento.
