package app.aemu

import android.content.Context
import android.content.res.Configuration
import app.aemu.core.VmSettings
import java.util.Locale

/** Настройки самого приложения (не отдельной системы): язык, тема, умолчания для новых систем. */
object AppPrefs {
    private const val FILE = "app"

    /** Языки интерфейса: код BCP-47 → самоназвание. Пустой код — как в системе. */
    val LANGUAGES = listOf(
        "" to "",
        "en" to "English",
        "ru" to "Русский",
        "uk" to "Українська",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pt-BR" to "Português (Brasil)",
        "it" to "Italiano",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "fa" to "فارسی",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
        "vi" to "Tiếng Việt",
        "zh-CN" to "简体中文",
        "ja" to "日本語",
        "ko" to "한국어",
    )

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun prefs(ctx: Context) = sp(ctx)

    fun language(ctx: Context): String = sp(ctx).getString("lang", "") ?: ""
    fun setLanguage(ctx: Context, tag: String) = sp(ctx).edit().putString("lang", tag).apply()

    fun theme(ctx: Context): Int = sp(ctx).getInt("theme", THEME_SYSTEM)
    fun setTheme(ctx: Context, v: Int) = sp(ctx).edit().putInt("theme", v).apply()

    fun dynamicColor(ctx: Context): Boolean = sp(ctx).getBoolean("dynamic", false)
    fun setDynamicColor(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dynamic", v).apply()

    /**
     * The navigation bar style from before VM screen themes (AEmulator-Plus #32), read from host-ui.json like
     * upstream. This fork never shipped the toggles, so the file is normally absent and both styles are off:
     * the pre-theme fallback in VmThemes.selected() is then the Original look this app has always had.
     */
    data class HostUiOptions(val holoNavbar: Boolean = false, val sunsetNavbar: Boolean = false)

    fun hostUiOptions(ctx: Context): HostUiOptions = runCatching {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "host-ui.json"))
        val json = org.json.JSONObject(file.openRead().bufferedReader().use { it.readText() })
        HostUiOptions(json.optBoolean("holoNavbar", false), json.optBoolean("sunsetNavbar", false))
    }.getOrDefault(HostUiOptions())

    /** The user's arrangement of the library (order + folders, AEmulator-Plus #33): a JSON sidecar, never part of the image data. */
    fun libraryLayout(ctx: Context): String? = sp(ctx).getString("library_layout", null)
    fun setLibraryLayout(ctx: Context, v: String) = sp(ctx).edit().putString("library_layout", v).apply()

    fun library3d(ctx: Context): Boolean = sp(ctx).getBoolean("library_3d", false)
    fun setLibrary3d(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("library_3d", v).apply()

    fun autoCheckUpdates(ctx: Context): Boolean = sp(ctx).getBoolean("auto_check_updates", true)
    fun setAutoCheckUpdates(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_check_updates", v).apply()

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_update_check", v).apply()

    /** Умолчания, которые получает только что импортированная система. */
    fun defaults(ctx: Context): VmSettings {
        val p = sp(ctx)
        val d = VmSettings()
        return d.copy(
            gpu = p.getBoolean("def_gpu", d.gpu),
            jit = p.getBoolean("def_jit", d.jit),
            netProxy = p.getBoolean("def_proxy", d.netProxy),
            showNavBar = p.getBoolean("def_nav", d.showNavBar),
            keepScreenOn = p.getBoolean("def_awake", d.keepScreenOn),
            showLed = p.getBoolean("def_led", d.showLed),
        )
    }

    fun setDefaults(ctx: Context, s: VmSettings) = sp(ctx).edit()
        .putBoolean("def_gpu", s.gpu).putBoolean("def_jit", s.jit).putBoolean("def_proxy", s.netProxy)
        .putBoolean("def_nav", s.showNavBar).putBoolean("def_awake", s.keepScreenOn).putBoolean("def_led", s.showLed).apply()

    /** Контекст с выбранным языком — для attachBaseContext каждой активности. */
    /**
     * Language and theme of the app applied to an activity's resources. The theme goes into the night bits of
     * uiMode: values-night (window background), isSystemInDarkTheme and enableEdgeToEdge's status bar icons then
     * follow the app's choice — before, a dark app theme on a light system kept a white window background and dark,
     * invisible status bar icons.
     */
    fun wrap(base: Context): Context {
        val tag = language(base)
        val night = when (theme(base)) {
            THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> -1
        }
        if (tag.isEmpty() && night < 0) return base
        val cfg = Configuration(base.resources.configuration)
        if (tag.isNotEmpty()) {
            val loc = Locale.forLanguageTag(tag)
            Locale.setDefault(loc)
            cfg.setLocale(loc)
            cfg.setLayoutDirection(loc)
        }
        if (night >= 0) cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }
}
