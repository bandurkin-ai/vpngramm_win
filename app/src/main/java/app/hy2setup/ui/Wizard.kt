package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel

private val STEPS = listOf("OneProvider", "name.com", "Имя и протоколы", "VPS")

/**
 * Мастер первого запуска (серверов ещё нет): ключи OneProvider → name.com и домен → имя и протоколы → VPS.
 * Каждый шаг проверяется, прежде чем идти дальше.
 */
@Composable
fun FirstRunWizard(vm: MainViewModel, onAddExisting: () -> Unit, startStep: Int = 0) {
    var step by rememberSaveable { mutableIntStateOf(startStep) }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.Rocket, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("VPN-сервер за 4 шага", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Шаг ${step + 1} из ${STEPS.size}: ${STEPS[step]}",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            LinearProgressIndicator(progress = { (step + 1f) / STEPS.size }, modifier = Modifier.fillMaxWidth())
        }
    }

    val checkedZone = vm.dnsLoaded && vm.loadedZone == vm.zone.trim().lowercase()
    val ok = when (step) {
        0 -> vm.opCatalogLoaded
        1 -> checkedZone
        2 -> vm.plannedFqdn.isNotEmpty() && vm.subdomain.isNotBlank() && (vm.plusHy2 || vm.plusVless || vm.plusAwg || vm.plusTg)
        else -> true
    }
    SectionCard(STEPS[step], collapsible = false, icon = when (step) { 0 -> AppIcons.Cloud; 1 -> AppIcons.Dns; 2 -> AppIcons.Globe; else -> AppIcons.Rocket }) {
        when (step) {
            0 -> {
                Text("Здесь покупаются серверы. Нужны ключи API из кабинета OneProvider.", style = MaterialTheme.typography.bodyMedium)
                OneProviderFields(vm)
                FilledTonalButton(onClick = vm::opLoadCatalog, enabled = !vm.running && vm.opApiKey.isNotBlank() && vm.opClientKey.isNotBlank()) {
                    Text("Проверить ключи")
                }
                if (vm.opCatalogLoaded) Done("Ключи работают: доступно локаций — ${vm.opLocationsOrderable.size}.")
                ErrorText(vm.opError)
            }
            1 -> {
                Text("Здесь ваш домен: приложение само направит имя сервера на его IP.", style = MaterialTheme.typography.bodyMedium)
                NameComFields(vm)
                FilledTonalButton(onClick = vm::loadDns, enabled = !vm.running && vm.ncUser.isNotBlank() && vm.ncToken.isNotBlank() && vm.zone.isNotBlank()) {
                    Text("Проверить доступ")
                }
                if (checkedZone) Done("Домен ${vm.loadedZone} найден, записей: ${vm.dnsRecords.size}.")
                ErrorText(vm.dnsError)
            }
            2 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppField("Поддомен", vm.subdomain, { vm.subdomain = it }, Modifier.weight(2f), KeyboardType.Uri, enabled = !vm.running)
                    AppField("TTL, с", vm.ttl, { vm.ttl = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
                }
                Hint(if (vm.plannedFqdn.isEmpty()) "Укажите поддомен." else "В ссылках будет: ${vm.plannedFqdn}")
                NewServerProtocols(vm)
                SwitchRow(
                    "Безопасность", vm.plusSecure, { vm.plusSecure = it }, enabled = !vm.running,
                    subtitle = if (vm.plusSecure) "В конце: вход только по SSH-ключу, обновления, файрвол, fail2ban" else "Вход по паролю root останется",
                )
            }
            else -> {
                Text("Выберите регион, локацию и тариф — сервер будет куплен и настроен (10–20 минут).", style = MaterialTheme.typography.bodyMedium)
                VpsOrderForm(vm)
                ErrorText(vm.plusError)
                Button(
                    onClick = { vm.plusNew = false; vm.plusRequest() },
                    enabled = !vm.running && vm.opCatalogLoaded, modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Icon(AppIcons.Rocket, null); Spacer(Modifier.width(10.dp)); Text("Создать сервер") }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (step > 0) OutlinedButton(onClick = { step-- }, enabled = !vm.running) { Text("Назад") }
        Spacer(Modifier.weight(1f))
        if (step < STEPS.lastIndex) Button(onClick = { vm.persist(); step++ }, enabled = ok && !vm.running) { Text("Далее") }
    }
    TextButton(onClick = onAddExisting, enabled = !vm.running) {
        Icon(AppIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("У меня уже есть сервер — добавить его")
    }
}

@Composable
private fun Done(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Check, null, tint = LocalStatusColors.current.success, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
