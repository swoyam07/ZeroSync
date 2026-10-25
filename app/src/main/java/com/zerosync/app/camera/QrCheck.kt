package com.zerosync.app.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.GeoParsedResult
import com.google.zxing.client.result.ISBNParsedResult
import com.google.zxing.client.result.ProductParsedResult
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.WifiParsedResult

/** How worried to be: shown as color/badge. */
enum class QrLevel { Info, Caution, Danger }

data class QrWarning(val level: QrLevel, val text: String)

/**
 * What a scanned code says, split into readable fields. Built ONLY from the code's text:
 * nothing is opened, visited or looked up (offline). [domain] = real site of a link.
 */
data class QrInfo(
    val type: String,
    val raw: String,
    val format: String,
    val fields: List<Pair<String, String>>,
    val warnings: List<QrWarning>,
    val domain: String? = null,
    /** Field whose value is hidden until tapped (Wi-Fi password). */
    val secretField: String? = null,
)

private val DangerSchemes = listOf("javascript:", "intent:", "data:", "file:", "content:", "vbscript:")
private val Shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "s.id", "rebrand.ly")
private val RiskyEndings = setOf("xyz", "top", "click", "loan", "work", "zip", "mov", "country", "gq", "tk", "ml", "cf", "ga", "rest", "cam", "monster")
private val Brands = listOf("esewa", "khalti", "fonepay", "imepay", "ncell", "ntc", "nabil", "nicasia", "nic-asia", "globalime", "everest", "himalayan", "kumari", "sanima", "siddhartha", "prabhu", "nmb", "laxmi", "machhapuchchhre", "rastriya", "paypal", "google", "facebook", "apple")

/** Read everything we can from a decoded code (offline, text only). */
fun checkQr(result: Result): QrInfo {
    val raw = result.text.orEmpty()
    val format = result.barcodeFormat.name.replace('_', ' ')
    val lower = raw.trim().lowercase()

    // 1) Dangerous "links" that are really code / app commands.
    DangerSchemes.firstOrNull { lower.startsWith(it) }?.let { scheme ->
        return QrInfo(
            "Dangerous link", raw, format, listOf("Kind" to scheme.removeSuffix(":")),
            listOf(QrWarning(QrLevel.Danger, "This is not a website. \"$scheme\" links are code or app commands that can run on your phone. Don't open it.")),
        )
    }
    // 2) Payment QR (EMV / NepalQR / Fonepay): starts with "000201".
    if (raw.startsWith("000201")) return payment(raw, format)
    // 3) Website links.
    if (lower.startsWith("http://") || lower.startsWith("https://")) return website(raw, format)
    // 4) Other app links (market://, upi://, esewa:// ...).
    Regex("^([a-z][a-z0-9+.-]{1,20}):").find(lower)?.let { m ->
        val scheme = m.groupValues[1]
        if (scheme !in setOf("tel", "sms", "smsto", "mailto", "matmsg", "wifi", "geo", "mecard", "begin")) {
            return QrInfo(
                "App link", raw, format, listOf("Opens app type" to scheme),
                listOf(QrWarning(QrLevel.Caution, "This tries to open another app (\"$scheme:\"). Only use it if you trust where it came from.")),
            )
        }
    }
    // 5) Everything else: let ZXing's parsers split the fields.
    return when (val p = ResultParser.parseResult(result)) {
        is WifiParsedResult -> QrInfo(
            "Wi-Fi", raw, format,
            listOfNotNull(
                "Network" to p.ssid.orEmpty(),
                "Security" to (p.networkEncryption?.ifBlank { null } ?: "Open (no password)"),
                p.password?.takeIf { it.isNotEmpty() }?.let { "Password" to it },
                if (p.isHidden) "Hidden network" to "Yes" else null,
            ),
            if (p.networkEncryption.isNullOrBlank() || p.networkEncryption.equals("nopass", true))
                listOf(QrWarning(QrLevel.Caution, "Open network: anyone nearby can see what you send on it.")) else emptyList(),
            secretField = "Password",
        )
        is TelParsedResult -> QrInfo("Phone number", raw, format, listOf("Number" to p.number.orEmpty()), emptyList())
        is SMSParsedResult -> QrInfo(
            "SMS", raw, format,
            listOfNotNull("To" to p.numbers.orEmpty().joinToString(), p.body?.takeIf { it.isNotBlank() }?.let { "Message" to it }),
            listOf(QrWarning(QrLevel.Info, "Nothing is sent. Copy it only if you want to send this yourself.")),
        )
        is EmailAddressParsedResult -> QrInfo(
            "Email", raw, format,
            listOfNotNull("To" to p.tos.orEmpty().joinToString(), p.subject?.let { "Subject" to it }, p.body?.let { "Message" to it }),
            emptyList(),
        )
        is AddressBookParsedResult -> QrInfo(
            "Contact card", raw, format,
            listOfNotNull(
                p.names?.firstOrNull()?.let { "Name" to it },
                p.phoneNumbers?.takeIf { it.isNotEmpty() }?.let { "Phone" to it.joinToString() },
                p.emails?.takeIf { it.isNotEmpty() }?.let { "Email" to it.joinToString() },
                p.org?.let { "Company" to it },
                p.addresses?.takeIf { it.isNotEmpty() }?.let { "Address" to it.joinToString() },
            ),
            listOf(QrWarning(QrLevel.Info, "Not saved. Add it to Contacts yourself if you want.")),
        )
        is GeoParsedResult -> QrInfo(
            "Location", raw, format,
            listOfNotNull("Latitude" to p.latitude.toString(), "Longitude" to p.longitude.toString(), p.query?.let { "Place" to it }),
            emptyList(),
        )
        is CalendarParsedResult -> QrInfo(
            "Calendar event", raw, format,
            listOfNotNull(
                p.summary?.let { "Title" to it },
                p.startTimestamp.takeIf { it > 0 }?.let { "Starts" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) },
                p.location?.let { "Place" to it },
            ),
            listOf(QrWarning(QrLevel.Info, "Not added to your calendar automatically.")),
        )
        is ISBNParsedResult -> QrInfo("Book (ISBN)", raw, format, listOf("ISBN" to p.isbn), emptyList())
        is ProductParsedResult -> QrInfo("Product barcode", raw, format, listOf("Code" to p.productID), emptyList())
        else -> QrInfo("Text", raw, format, emptyList(), emptyList())
    }
}

/** Website link: real domain + offline warning rules. */
private fun website(raw: String, format: String): QrInfo {
    val warnings = mutableListOf<QrWarning>()
    val lower = raw.trim().lowercase()
    if (lower.startsWith("http://")) warnings += QrWarning(QrLevel.Caution, "Not secure (http, not https): anything you type there can be read by others.")

    // Host = part between "://" and the next "/", "?" or "#", with anything before "@" dropped.
    var host = lower.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in host) {
        warnings += QrWarning(QrLevel.Danger, "Hidden address trick: everything before \"@\" is ignored. The real site is the part after it.")
        host = host.substringAfterLast('@')
    }
    host = host.substringBefore(':') // drop port
    val real = realDomain(host)

    if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) warnings += QrWarning(QrLevel.Danger, "Uses a raw number address instead of a name. Real banks and shops use names.")
    if ("xn--" in host) warnings += QrWarning(QrLevel.Danger, "Look-alike letters: the name uses foreign characters that can imitate a real site.")
    if (real in Shorteners) warnings += QrWarning(QrLevel.Caution, "Shortened link: it hides where it really goes.")
    if (host.substringAfterLast('.') in RiskyEndings) warnings += QrWarning(QrLevel.Caution, "\".${host.substringAfterLast('.')}\" ending is often used by scam sites.")
    Brands.firstOrNull { it in host && !real.startsWith(it) }?.let { brand ->
        warnings += QrWarning(QrLevel.Danger, "Mentions \"$brand\" but the real site is $real, not $brand.")
    }
    if (raw.length > 200) warnings += QrWarning(QrLevel.Caution, "Very long link (${raw.length} characters), often used to confuse.")
    if (host.count { it == '.' } >= 4 || host.count { it == '-' } >= 3) warnings += QrWarning(QrLevel.Caution, "Messy address with many parts.")
    if (warnings.isEmpty()) warnings += QrWarning(QrLevel.Info, "No warning signs found, but ZeroSync can't check online. Make sure you know $real.")

    return QrInfo(
        "Website link", raw, format,
        listOf("Real site" to real, "Secure" to if (lower.startsWith("https://")) "Yes (https)" else "No (http)"),
        warnings, domain = real,
    )
}

/** Last 2 parts of the host, or 3 for country domains like esewa.com.np. */
private fun realDomain(host: String): String {
    val parts = host.split('.').filter { it.isNotEmpty() }
    if (parts.size <= 2) return host
    val secondLevel = setOf("com", "org", "net", "edu", "gov", "co", "ac", "mil")
    val n = if (parts[parts.size - 2] in secondLevel && parts.last().length == 2) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

/** Payment QR (EMV standard used by Fonepay / NepalQR / banks): labelled fields "TTLLvalue". */
private fun payment(raw: String, format: String): QrInfo {
    val tags = HashMap<String, String>()
    var i = 0
    while (i + 4 <= raw.length) {
        val tag = raw.substring(i, i + 2)
        val len = raw.substring(i + 2, i + 4).toIntOrNull() ?: break
        if (i + 4 + len > raw.length) break
        tags[tag] = raw.substring(i + 4, i + 4 + len)
        i += 4 + len
    }
    // Account / network info lives in tags 26..51; its sub-tag "00" names the network.
    val network = (26..51).mapNotNull { tags["%02d".format(it)] }.firstNotNullOfOrNull { sub ->
        if (sub.startsWith("00")) sub.substring(4, 4 + (sub.substring(2, 4).toIntOrNull() ?: 0).coerceAtMost(sub.length - 4)) else null
    }
    val currency = when (tags["53"]) { "524" -> "NPR"; "356" -> "INR"; "840" -> "USD"; null -> null; else -> tags["53"] }
    return QrInfo(
        "Payment request", raw, format,
        listOfNotNull(
            tags["59"]?.let { "Merchant" to it },
            tags["60"]?.let { "City" to it },
            tags["54"]?.let { "Amount" to listOfNotNull(currency, it).joinToString(" ") },
            if (tags["54"] == null) "Amount" to "Not fixed (you type it)" else null,
            network?.let { "Network" to it },
            tags["58"]?.let { "Country" to it },
        ),
        listOf(QrWarning(QrLevel.Caution, "This is a payment request. Only pay if you know who it's from. ZeroSync can't confirm the account is real.")),
    )
}
