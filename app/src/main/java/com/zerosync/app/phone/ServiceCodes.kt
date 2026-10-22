package com.zerosync.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

/** Quick network codes (USSD). Dialed from the SIM of that network. */
val ServiceCodes: Map<Carrier, List<Pair<String, String>>> = mapOf(
    Carrier.Ntc to listOf(
        "Balance" to "*400#",
        "Pack" to "*1415#",
        "Data balance" to "*1414*55#",
        "Credit" to "*1477#",
    ),
    Carrier.Ncell to listOf(
        "Balance" to "*901#",
        "Pack" to "*123#",
        "Credit" to "*9988#",
    ),
)

/** Small pill button in the network color, e.g. "NTC" (blue) / "Ncell" (purple). */
@Composable
fun NetworkButton(carrier: Carrier, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        carrier.label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(carrier.color))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** List of that network's codes; tap one = dial it from that network's SIM. */
@Composable
fun ServiceCodesDialog(carrier: Carrier, onDial: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text(carrier.label, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ServiceCodes[carrier].orEmpty().forEach { (name, code) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .clickable { onDial(code) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = Color.White, modifier = Modifier.weight(1f))
                        // Lighter tint of the network color so the code reads well on black.
                        Text(code, color = if (carrier == Carrier.Ntc) Color(0xFF8FA8FF) else Color(0xFFD7A6E0))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = ZsColors.Secondary) } },
    )
}
