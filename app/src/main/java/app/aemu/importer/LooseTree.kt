package app.aemu.importer

/**
 * The root of a loose system tree inside an archive: the directory prefix in front of
 * "system/build.prop" ("" when the tree sits at the archive root, where the plain
 * "system/…" claim has always seen it).
 *
 * A fastboot repack buries the tree one or two folders deep ("<pkg>/Firmware + Tool/system/…"),
 * where a claim that only accepts names starting with "system/" cannot see it. The build.prop
 * anchor is what keeps an image file or a stray folder named "system" from being mistaken for
 * the tree: only a real tree carries that file. With several anchors the tree with the most
 * entries under it wins; a tie goes to the shallower prefix.
 *
 * [names] are the archive's entry names, already normalised ('\\' → '/') and with any wrapper
 * folder the caller strips (the OTA one) already removed.
 */
internal fun looseTreePrefix(names: List<String>): String {
    val anchor = Regex("(.+/)?system/build\\.prop")
    val candidates = names.filter { anchor.matches(it) }
        .map { it.removeSuffix("system/build.prop") }
        .distinct()
    if (candidates.isEmpty()) return ""
    return candidates.sortedWith(
        compareByDescending<String> { p -> names.count { n -> n.startsWith(p + "system/") } }
            .thenBy { it.length }
    ).first()
}
