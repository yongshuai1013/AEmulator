/* AEmulator-Plus (somerandomusernam2), 2026-10: the NavButton enum from core/NavControls.kt, ported for the
 * VM screen themes (#32) — device skins name their hardware keys with these stable IDs. GPL-3.0. */
package app.aemu.core

/** Stable IDs in image metadata; unknown IDs and duplicates are ignored. */
enum class NavButton(val id: String, val scanCode: Int) {
    BACK("back", 158), HOME("home", 102), RECENTS("recents", 580), MENU("menu", 139),
    SEARCH("search", 217), POWER("power", 116), VOLUME_DOWN("volume_down", 114),
    VOLUME_UP("volume_up", 115), UP("up", 103), DOWN("down", 108),
    LEFT("left", 105), RIGHT("right", 106), CENTER("center", 353);
}
