package app.aemu.core

/**
 * Carrier presets for the fake modem's operator identity: the list the settings page offers.
 *
 * Names and regions come from Ackites/Nrfr (Apache-2.0): https://github.com/Ackites/Nrfr
 * PLMN codes (MCC+MNC) are the ITU-assigned public allocations for each carrier; entries
 * marked "verify" are circle/region-dependent or less certain — double-check before relying on them.
 */
data class CarrierPreset(
    /** local-language name, as listed by Nrfr */
    val name: String,
    /** the name the guest framework shows */
    val displayName: String,
    /** ISO country code */
    val region: String,
    /** MCC+MNC digits */
    val plmn: String,
)

object CarrierPresets {
    val presets = listOf(
        // China mainland
        CarrierPreset("中国移动", "China Mobile", "CN", "46000"),
        CarrierPreset("中国联通", "China Unicom", "CN", "46001"),
        CarrierPreset("中国电信", "China Telecom", "CN", "46003"),
        // Hong Kong
        CarrierPreset("中国移动香港", "CMHK", "HK", "45412"),
        CarrierPreset("香港电讯", "HKT", "HK", "45400"),
        CarrierPreset("3香港", "3HK", "HK", "45403"),
        CarrierPreset("SmarTone", "SmarTone", "HK", "45406"),
        // Macau
        CarrierPreset("澳门电讯", "CTM", "MO", "45501"),
        CarrierPreset("3澳门", "3 Macau", "MO", "45503"),   // verify
        // Taiwan
        CarrierPreset("中华电信", "Chunghwa Telecom", "TW", "46692"),
        CarrierPreset("台湾大哥大", "Taiwan Mobile", "TW", "46697"),
        CarrierPreset("远传电信", "FarEasTone", "TW", "46601"),
        // Japan
        CarrierPreset("NTT docomo", "NTT docomo", "JP", "44010"),
        CarrierPreset("au", "au by KDDI", "JP", "44007"),
        CarrierPreset("Softbank", "Softbank", "JP", "44020"),
        CarrierPreset("Rakuten", "Rakuten Mobile", "JP", "44011"),
        // Korea
        CarrierPreset("SK Telecom", "SK Telecom", "KR", "45005"),
        CarrierPreset("KT", "KT Corporation", "KR", "45008"),
        CarrierPreset("LG U+", "LG U+", "KR", "45006"),
        // United States
        CarrierPreset("AT&T", "AT&T", "US", "310410"),
        CarrierPreset("T-Mobile", "T-Mobile USA", "US", "310260"),
        CarrierPreset("Verizon", "Verizon", "US", "311480"),
        CarrierPreset("Sprint", "Sprint", "US", "310120"),
        // United Kingdom
        CarrierPreset("EE", "EE", "GB", "23430"),
        CarrierPreset("O2", "O2 UK", "GB", "23410"),
        CarrierPreset("Three", "Three UK", "GB", "23420"),
        CarrierPreset("Vodafone", "Vodafone UK", "GB", "23415"),
        // Singapore
        CarrierPreset("Singtel", "Singtel", "SG", "52501"),
        CarrierPreset("StarHub", "StarHub", "SG", "52505"),
        CarrierPreset("M1", "M1", "SG", "52503"),
        // Malaysia
        CarrierPreset("Maxis", "Maxis", "MY", "50212"),
        CarrierPreset("Celcom", "Celcom", "MY", "50213"),
        CarrierPreset("Digi", "Digi", "MY", "50216"),
        CarrierPreset("U Mobile", "U Mobile", "MY", "50218"),
        // Thailand
        CarrierPreset("AIS", "AIS", "TH", "52001"),
        CarrierPreset("DTAC", "DTAC", "TH", "52018"),
        CarrierPreset("True Move H", "True Move H", "TH", "52000"),
        // Vietnam
        CarrierPreset("Viettel", "Viettel Mobile", "VN", "45204"),
        CarrierPreset("Vinaphone", "Vinaphone", "VN", "45202"),
        CarrierPreset("Mobifone", "Mobifone", "VN", "45201"),
        // Indonesia
        CarrierPreset("Telkomsel", "Telkomsel", "ID", "51010"),
        CarrierPreset("Indosat", "Indosat Ooredoo", "ID", "51001"),
        CarrierPreset("XL Axiata", "XL Axiata", "ID", "51011"),
        // Philippines
        CarrierPreset("Globe", "Globe Telecom", "PH", "51502"),
        CarrierPreset("Smart", "Smart Communications", "PH", "51503"),
        CarrierPreset("DITO", "DITO Telecommunity", "PH", "51566"),
        // India (PLMN depends on the circle — verify)
        CarrierPreset("Jio", "Reliance Jio", "IN", "405840"),   // verify
        CarrierPreset("Airtel", "Bharti Airtel", "IN", "40410"),   // verify
        CarrierPreset("Vi", "Vodafone Idea", "IN", "40411"),   // verify
        // Australia
        CarrierPreset("Telstra", "Telstra", "AU", "50501"),
        CarrierPreset("Optus", "Optus", "AU", "50502"),
        CarrierPreset("Vodafone", "Vodafone AU", "AU", "50503"),
        // Canada
        CarrierPreset("Bell", "Bell Mobility", "CA", "302610"),
        CarrierPreset("Rogers", "Rogers Wireless", "CA", "302720"),
        CarrierPreset("Telus", "Telus Mobility", "CA", "302220"),
        // Germany
        CarrierPreset("Telekom", "T-Mobile DE", "DE", "26201"),
        CarrierPreset("Vodafone", "Vodafone DE", "DE", "26202"),
        CarrierPreset("O2", "O2 DE", "DE", "26207"),
        // France
        CarrierPreset("Orange", "Orange FR", "FR", "20801"),
        CarrierPreset("SFR", "SFR", "FR", "20810"),
        CarrierPreset("Free", "Free Mobile", "FR", "20815"),
        CarrierPreset("Bouygues", "Bouygues Telecom", "FR", "20820"),
        // Italy
        CarrierPreset("TIM", "Telecom Italia", "IT", "22201"),
        CarrierPreset("Vodafone", "Vodafone IT", "IT", "22210"),
        CarrierPreset("Wind Tre", "Wind Tre", "IT", "22299"),
        // Spain
        CarrierPreset("Movistar", "Movistar", "ES", "21407"),
        CarrierPreset("Vodafone", "Vodafone ES", "ES", "21401"),
        CarrierPreset("Orange", "Orange ES", "ES", "21403"),
        // Russia
        CarrierPreset("MTS", "MTS", "RU", "25001"),
        CarrierPreset("MegaFon", "MegaFon", "RU", "25002"),
        CarrierPreset("Beeline", "Beeline", "RU", "25099"),
        // Brazil (some PLMNs depend on the region — verify)
        CarrierPreset("Vivo", "Vivo", "BR", "72406"),   // verify
        CarrierPreset("Claro", "Claro", "BR", "72405"),
        CarrierPreset("TIM", "TIM Brasil", "BR", "72402"),   // verify
    )

    fun byPlmn(plmn: String): CarrierPreset? = presets.firstOrNull { it.plmn == plmn }
}
