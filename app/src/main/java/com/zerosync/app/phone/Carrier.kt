package com.zerosync.app.phone

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.zerosync.app.R

/** Nepal mobile networks we recognise from the first 3 digits of a number. */
enum class Carrier(val label: String, @param:DrawableRes val logo: Int, val prefixes: List<String>) {
    // 984/985/986 GSM, 974/975 CDMA (Sky), 976 4G.
    Ntc("NTC", R.drawable.carrier_ntc, listOf("984", "985", "986", "974", "975", "976")),
    Ncell("Ncell", R.drawable.carrier_ncell, listOf("980", "981", "982", "970")),
}

/**
 * The same number written two ways counts as one: "+977 984-1000000", "009779841000000" and
 * "9841000000" all become "9841000000" (digits only, Nepal country code removed).
 */
fun localNumber(number: String): String {
    val digits = number.filter { it.isDigit() }
    return when {
        digits.startsWith("00977") -> digits.drop(5)
        digits.startsWith("977") && digits.length > 10 -> digits.drop(3)
        else -> digits
    }
}

/**
 * Which network a number belongs to, or null if unknown (e.g. landlines like 01...).
 * Ignores spaces/dashes and the Nepal country code (+977 / 00977).
 */
fun carrierOf(number: String): Carrier? {
    val prefix = localNumber(number).take(3)
    return Carrier.entries.firstOrNull { prefix in it.prefixes }
}

/** Small network logo for [number]; shows nothing when the network is unknown. */
@Composable
fun CarrierLogo(number: String, modifier: Modifier = Modifier) {
    val carrier = carrierOf(number) ?: return
    Image(
        painterResource(carrier.logo),
        contentDescription = carrier.label,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
