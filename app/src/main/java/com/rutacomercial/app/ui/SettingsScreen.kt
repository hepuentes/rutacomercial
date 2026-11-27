package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.DataSnapshot
import com.rutacomercial.app.BuildConfig

@Composable
internal fun SettingsScreen(vm:RutaViewModel,data:DataSnapshot,onBackup:(String)->Unit,onRestore:(String)->Unit,onExport:(String,String?)->Unit,onPermissions:()->Unit)=ScreenColumn {
    val context=LocalContext.current
    val preferences=remember{context.getSharedPreferences("privacy",0)}
    var retention by remember{mutableStateOf(preferences.getInt("retentionDays",365).toString())}
    var passwordMode by remember{mutableStateOf<String?>(null)}
    var deleteConfirm by remember{mutableStateOf(false)}
    PageHeading("Configuración y privacidad","El teléfono conserva la información de tu operación.")
    val cloud=(context.applicationContext as com.rutacomercial.app.RutaApplication).cloud
    if(!com.rutacomercial.app.BuildConfig.WORKER_EDITION && cloud.enabled) OwnerGoogleAccount(cloud)
    InfoCard("Seguimiento solo durante la jornada","La ubicación se registra cuando iniciás expresamente una jornada. Pausar o finalizar detiene el seguimiento. No se inicia por encender el teléfono. Android puede interrumpir el servicio; esas interrupciones quedan señaladas y no se inventan segmentos.")
    Button(onClick=onPermissions,modifier=Modifier.fillMaxWidth()){Text("Revisar permisos de Android")}
    InfoCard("Dónde están tus datos","Negocios, teléfonos, fotografías y ubicaciones se guardan en el almacenamiento privado de la aplicación. No se sincronizan con un servidor. El proveedor del mapa recibe las solicitudes cartográficas necesarias. Evitá datos personales innecesarios en fotos y comentarios.")
    InfoCard("Respaldo cifrado","Guardá una copia con una contraseña de al menos 12 caracteres. Conservá la contraseña por separado: no existe recuperación automática. La copia incluye registros y fotografías adjuntas; las regiones de mapa se descargan nuevamente.")
    Button(onClick={passwordMode="backup"},modifier=Modifier.fillMaxWidth()){Text("Crear respaldo cifrado")}
    OutlinedButton(onClick={passwordMode="restore"},modifier=Modifier.fillMaxWidth()){Text("Restaurar respaldo")}
    Text("La restauración solo se permite en una instalación sin registros. Si hay datos, se rechaza sin borrarlos ni sobrescribirlos. El archivo se comprueba íntegramente antes de importar.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Informes para guardar o compartir",style=MaterialTheme.typography.titleLarge)
    var routeExport by remember { mutableStateOf(false) }
    OutlinedButton(onClick={routeExport=!routeExport},modifier=Modifier.fillMaxWidth()) { Text("Recorridos por fecha (CSV)") }
    if(routeExport) {
        var from by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        var until by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        RouteExport(data,zoneWork(),from,until) { a,b -> from=a;until=b }
    }
    OutlinedButton(onClick={onExport("csv",null)},modifier=Modifier.fillMaxWidth()){Text("Exportar negocios y visitas (CSV)")}
    OutlinedButton(onClick={onExport("inventory_csv",null)},modifier=Modifier.fillMaxWidth()){Text("Exportar movimientos de inventario (CSV)")}
    OutlinedButton(onClick={onExport("gpx",null)},modifier=Modifier.fillMaxWidth()){Text("Exportar recorridos (GPX)")}
    OutlinedButton(onClick={onExport("geojson",null)},modifier=Modifier.fillMaxWidth()){Text("Exportar recorridos (GeoJSON)")}
    Text("Los informes exportados no están cifrados y pueden incluir datos comerciales o ubicaciones. Elegí dónde guardarlos y con quién compartirlos.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Conservación de ubicaciones",style=MaterialTheme.typography.titleLarge)
    Field("Días de puntos GPS que querés conservar",retention,{retention=it},numeric=true)
    Text("La limpieza es manual: elimina puntos GPS de jornadas finalizadas anteriores al plazo. Conserva los resúmenes de jornada, las visitas, los movimientos y la auditoría. Hacé un respaldo antes.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={
        val days=retention.toIntOrNull()
        if(days==null || days !in 1..36500)vm.message("Ingresá un plazo de 1 a 36500 días.")
        else {preferences.edit().putInt("retentionDays",days).apply();deleteConfirm=true}
    },modifier=Modifier.fillMaxWidth()){Text("Revisar eliminación de puntos antiguos")}
    Text("${data.points.size} puntos GPS guardados · ${data.audit.size} eventos de auditoría",style=MaterialTheme.typography.bodySmall)
    InfoCard("Primera etapa: uso individual","La persona responsable es el propietario de este teléfono. No hay cuentas remotas, supervisión en vivo ni sincronización multiusuario. La revisión de declaraciones es humana; ninguna señal aislada prueba fraude.")
    Text("RutaComercial · versión de pruebas ${BuildConfig.VERSION_NAME}\nMapas: MapLibre · © OpenFreeMap · © OpenMapTiles · © colaboradores de OpenStreetMap",style=MaterialTheme.typography.bodySmall)
    passwordMode?.let{mode->PasswordDialog(mode=="backup",onClose={passwordMode=null}) { password->
        passwordMode=null
        if(mode=="backup")onBackup(password)else onRestore(password)
    }}
    if(deleteConfirm)ConfirmDialog("Eliminar puntos GPS antiguos","Se borrarán de este teléfono los puntos de jornadas finalizadas hace más de $retention días. Los resúmenes y registros comerciales se conservan. Esta acción no se puede deshacer sin un respaldo.","Eliminar puntos",{deleteConfirm=false}) {
        val days=retention.toLong()
        vm.perform(onSuccess={deleteConfirm=false}) {
            val count=vm.repository.deleteOldTracks(System.currentTimeMillis()-days*86_400_000L)
            vm.message("Se eliminaron $count puntos GPS antiguos.")
        }
    }
}

@Composable
private fun PasswordDialog(create:Boolean,onClose:()->Unit,onConfirm:(String)->Unit) {
    var password by remember{mutableStateOf("")}
    var repeat by remember{mutableStateOf("")}
    val valid=password.length>=12 && (!create || password==repeat)
    EditorDialog(if(create)"Contraseña del respaldo" else "Abrir respaldo cifrado",onClose,{onConfirm(password)},if(create)"Elegir destino" else "Elegir archivo",enabled=valid) {
        Text(if(create)"Usá una frase larga que puedas guardar de forma segura. Se necesita la misma contraseña para restaurar." else "Ingresá la contraseña del archivo. La restauración se rechazará si este teléfono ya contiene registros.")
        OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Contraseña (mínimo 12 caracteres)")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        if(create)OutlinedTextField(repeat,{repeat=it},Modifier.fillMaxWidth(),label={Text("Repetir contraseña")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        if(password.isNotEmpty() && password.length<12)Text("Faltan ${12-password.length} caracteres.",style=MaterialTheme.typography.bodySmall)
        if(create && repeat.isNotEmpty() && repeat!=password)Text("Las contraseñas deben coincidir.",color=MaterialTheme.colorScheme.error)
    }
}
