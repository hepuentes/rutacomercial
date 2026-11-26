package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Inventory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Composable
internal fun CampaignsScreen(vm:RutaViewModel,data:DataSnapshot)=ScreenColumn {
    var editing by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<Campaign?>(null)}
    var movementCampaign by remember{mutableStateOf<Campaign?>(null)}
    var ledgerCampaign by remember{mutableStateOf<Campaign?>(null)}
    PageHeading("Campañas e inventario","Cada entrada, asignación, entrega y devolución queda registrada.")
    Button(onClick={selected=null;editing=true},modifier=Modifier.fillMaxWidth()){Text("Crear campaña")}
    InfoCard("Antes de salir","Cargá el inventario y asigná tarjetas para repartir. Las visitas consumen el material asignado; al volver, registrá las tarjetas dañadas y las devoluciones.")
    if(data.campaigns.isEmpty())InfoCard("Sin campañas","Creá tu primera campaña con su inventario inicial. No se permite entregar más material que el disponible.")
    data.campaigns.sortedByDescending{it.startsAt}.forEach{c->
        val balance=Inventory.balance(data.movements.filter{it.campaignId==c.id})
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(c.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                if(c.description.isNotBlank())Text(c.description)
                Text("${dateOnly(c.startsAt)}${c.endsAt?.let{" a ${dateOnly(it)}"} ?: " · sin fecha final"}")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MetricCard("En depósito",balance.depot.toString(),Modifier.weight(1f))
                    MetricCard("Para repartir",balance.carried.toString(),Modifier.weight(1f))
                }
                Text("${balance.delivered} entregadas declaradas · ${balance.damaged} dañadas")
                c.unitCostCents?.let{Text("Costo por tarjeta: $ ${number(it / 100.0,2)}")}
                Button(onClick={movementCampaign=c},modifier=Modifier.fillMaxWidth()){Text("Registrar movimiento")}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={selected=c;editing=true}){Text("Editar campaña")}
                    TextButton(onClick={ledgerCampaign=c}){Text("Ver movimientos")}
                }
            }
        }
    }
    if(editing)CampaignEditor(vm,selected){editing=false}
    movementCampaign?.let{c->MovementEditor(vm,data,c){movementCampaign=null}}
    ledgerCampaign?.let{c->LedgerDialog(data,c){ledgerCampaign=null}}
}

@Composable
private fun CampaignEditor(vm:RutaViewModel,existing:Campaign?,onClose:()->Unit) {
    val id=remember{existing?.id ?: newId()}
    var name by remember{mutableStateOf(existing?.name ?: "")}
    var description by remember{mutableStateOf(existing?.description ?: "")}
    var starts by remember{mutableStateOf(existing?.let{dateOnly(it.startsAt)} ?: LocalDate.now().toString())}
    var ends by remember{mutableStateOf(dateOnly(existing?.endsAt))}
    var stock by remember{mutableStateOf("0")}
    var cost by remember{mutableStateOf(existing?.unitCostCents?.let{BigDecimal(it).movePointLeft(2).toPlainString()} ?: "")}
    EditorDialog(if(existing==null)"Nueva campaña" else "Editar campaña",onClose,{vm.perform("Campaña guardada",onClose){
        val unitCost=if(cost.isBlank())null else try{BigDecimal(cost.replace(',','.')).movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact()}catch(e:Exception){throw IllegalArgumentException("Ingresá un costo válido con hasta dos decimales.")}
        val start=parseDate(starts) ?: throw IllegalArgumentException("Ingresá la fecha de inicio.")
        val initial=if(existing==null)stock.toIntOrNull() ?: throw IllegalArgumentException("El inventario inicial debe ser un número entero.") else 0
        vm.repository.saveCampaign((existing ?: Campaign(id=id,name=name)).copy(name=name,description=description,startsAt=start,endsAt=parseDate(ends),unitCostCents=unitCost),initialStock=initial)
    }}) {
        Field("Nombre de campaña",name,{name=it});Field("Descripción",description,{description=it},multiline=true)
        Field("Inicio (AAAA-MM-DD)",starts,{starts=it});Field("Fin opcional (AAAA-MM-DD)",ends,{ends=it})
        if(existing==null)Field("Tarjetas iniciales en depósito",stock,{stock=it},numeric=true)
        Field("Costo por tarjeta, opcional ($)",cost,{cost=it},numeric=true)
        Text("El material se concilia con movimientos. Editar la campaña no cambia el inventario existente.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MovementEditor(vm:RutaViewModel,data:DataSnapshot,campaign:Campaign,onClose:()->Unit) {
    val id=remember{newId()}
    var type by remember{mutableStateOf("ASSIGN")}
    var quantity by remember{mutableStateOf("")}
    var note by remember{mutableStateOf("")}
    val balance=Inventory.balance(data.movements.filter{it.campaignId==campaign.id})
    EditorDialog("Movimiento de material",onClose,{vm.perform("Movimiento registrado",onClose){
        val amount=quantity.toIntOrNull() ?: throw IllegalArgumentException("Ingresá una cantidad entera de tarjetas.")
        vm.repository.moveStock(StockMovement(id=id,campaignId=campaign.id,type=type,quantity=amount,operationId=id,journeyId=data.activeJourney?.id,note=note))
    }},"Registrar") {
        Text(campaign.name,fontWeight=FontWeight.Bold)
        Text("Depósito: ${balance.depot} · Asignadas: ${balance.carried}")
        Choice("Tipo de movimiento",type,MovementType.entries.filter{it.name in listOf("ENTRY","ASSIGN","DAMAGE","RETURN")}.map{it.name to it.label}){type=it}
        Field("Cantidad",quantity,{quantity=it},numeric=true);Field("Detalle o referencia",note,{note=it},multiline=true)
        Text(when(type){"ENTRY"->"Aumenta el material en depósito.";"ASSIGN"->"Transfiere del depósito al material que llevás para repartir.";"DAMAGE"->"Descuenta tarjetas dañadas del material asignado.";else->"Devuelve material asignado al depósito."})
        Text("Las entregas se registran desde el mapa a un toque o desde la ficha de un negocio.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LedgerDialog(data:DataSnapshot,campaign:Campaign,onClose:()->Unit) {
    val movements=data.movements.filter{it.campaignId==campaign.id}.sortedByDescending{it.at}
    AlertDialog(onDismissRequest=onClose,title={Text("Movimientos · ${campaign.name}")},text={
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(movements.size){index->val m=movements[index]
                Column {
                    Text("${MovementType.entries.firstOrNull{it.name==m.type}?.label ?: when(m.type) { "QUICK_DELIVERY" -> "Entrega a un toque"; "QUICK_UNDO" -> "Entrega anulada"; else -> m.type }}: ${m.quantity}",fontWeight=FontWeight.SemiBold)
                    Text(dateText(m.at),style=MaterialTheme.typography.bodySmall)
                    if(m.note.isNotBlank())Text(m.note)
                    Text("Operación ${m.operationId}",style=MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                }
            }
            if(movements.isEmpty())item{Text("No hay movimientos registrados.")}
        }
    },confirmButton={TextButton(onClick=onClose){Text("Cerrar")}})
}
