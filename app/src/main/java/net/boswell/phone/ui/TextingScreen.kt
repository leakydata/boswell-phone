package net.boswell.phone.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.boswell.phone.assistant.TextContact
import net.boswell.phone.assistant.Texting

/**
 * Texting with chosen contacts: the switch, Android's permissions (with the
 * way through "restricted settings" for an app not from the Play Store), and
 * the list of people the assistant may read texts from and text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Texting.enabled(ctx)) }
    var people by remember { mutableStateOf(Texting.contacts(ctx)) }
    var granted by remember { mutableStateOf(Texting.canRead(ctx) && Texting.canSend(ctx)) }
    var refused by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    fun save(list: List<TextContact>) { people = list; Texting.setContacts(ctx, list) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = Texting.canRead(ctx) && Texting.canSend(ctx)
        refused = !granted
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) save(people + TextContact(c.getString(0) ?: "Contact", c.getString(1) ?: return@use))
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Texting") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } })
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let the assistant text", style = MaterialTheme.typography.titleMedium)
                        Text("It can read your texts with the people below, and text them -- but a text is only sent after you tap Send or say \\u201cyes, send it\\u201d. Nobody else, ever.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = { on = it; Texting.setEnabled(ctx, it) })
                }
            }
            if (on && !granted) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Android needs to allow reading and sending texts.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { perms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)) }) { Text("Allow texts") }
                    if (refused) {
                        Text("If Android won't ask (it says the setting is restricted): open App info, tap ⋮ (top right), choose \\u201cAllow restricted settings\\u201d, then come back and tap Allow texts again. Apps installed outside the Play Store need this once.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Open App info") }
                    }
                }
            }
            item { Text("Contacts it may text", style = MaterialTheme.typography.titleMedium) }
            if (people.isEmpty()) item { Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(people, key = { Texting.digits(it.number) }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(p.number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { save(people - p) }) { Text("Remove") }
                }
            }
            item {
                OutlinedButton(onClick = { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) { Text("Choose from contacts") }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Or add one by hand", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Number") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = name.isNotBlank() && Texting.digits(number).length >= 7, onClick = {
                            save(people + TextContact(name.trim(), number.trim())); name = ""; number = ""
                        }) { Text("Add") }
                    }
                }
            }
        }
    }
}
