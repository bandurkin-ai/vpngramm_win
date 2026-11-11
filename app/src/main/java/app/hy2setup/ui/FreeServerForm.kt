package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel

/**
 * Данные входа на добавляемый сервер: порт, пользователь, пароль или SSH-ключ.
 * Общие для «Добавить сервер по IP» (свободные серверы) и «Добавить работающий сервер».
 */
@Composable
internal fun ServerLoginFields(vm: MainViewModel) {
    val keyPicker = rememberOpenDoc { vm.freeLoadKeyFrom(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppField("Порт", vm.freePort, { vm.freePort = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
        AppField("Пользователь", vm.freeUser, { vm.freeUser = it }, Modifier.weight(2f), enabled = !vm.running)
    }
    Segmented(listOf(false to "Пароль", true to "SSH-ключ"), vm.freeUseKey, { vm.freeUseKey = it }, enabled = !vm.running)
    if (!vm.freeUseKey) {
        AppField("Пароль", vm.freePassword, { vm.freePassword = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
    } else {
        AppField("Приватный ключ (вставьте текст)", vm.freeKey, { vm.freeKey = it }, lines = 3, mono = true, enabled = !vm.running)
        OutlinedButton(onClick = { keyPicker() }, enabled = !vm.running) { Text("Выбрать файл ключа…") }
        AppField("Пароль от ключа (если есть)", vm.freePassphrase, { vm.freePassphrase = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
        if (vm.freeUser.trim() != "root") {
            AppField("Пароль для sudo", vm.freePassword, { vm.freePassword = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
        }
    }
}

/**
 * «Добавить сервер по IP»: любой сервер по адресу и паролю или SSH-ключу попадает в «Свободные серверы»,
 * и на него можно переехать. Сам сервер при добавлении не изменяется.
 */
@Composable
internal fun FreeServerForm(vm: MainViewModel, onAdded: () -> Unit = {}) {
    AppField("IP-адрес сервера", vm.importHost, { vm.importHost = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
    ServerLoginFields(vm)
    Hint("Приложение войдёт на сервер и запомнит данные входа (зашифрованно). Сам сервер не меняется, пока вы на него не переедете. " +
        "Если пароль и ключ не указаны, берутся сохранённые для этого адреса.")
    ErrorText(vm.freeError)
    Button(onClick = { vm.addFreeServer(onAdded) }, enabled = !vm.running, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (vm.freeBusy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp)); Text("Проверяю сервер…")
        } else {
            Icon(AppIcons.Add, null); Spacer(Modifier.width(10.dp)); Text("Добавить в свободные серверы")
        }
    }
}
