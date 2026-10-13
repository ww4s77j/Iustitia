package dev.iustitia.util

import java.nio.file.Files
import java.nio.file.Path

/**
 * One-time recovery of store files written by builds up to v1.4.0, when the mod's data lived in the
 * per-user roaming directory (`%APPDATA%/.iustitia` on Windows). From v1.4.1 every store lives inside
 * the game's own folder (`<gameDir>/.iustitia`), so without this the old files would simply become
 * invisible — an empty clip manager or preset list right after upgrading.
 *
 * [migrate] COPIES matching files from the legacy `%APPDATA%/.iustitia/<subdir>` into [target], once,
 * guarded by a `.legacy-migrated` marker in [target]. The marker (not the file contents) decides
 * "already done", so a clip/preset the user deletes afterwards is not resurrected on the next launch.
 * The legacy side is only ever READ — nothing is written or deleted there, so an old install (or an
 * older Iustitia build sharing the machine) keeps working untouched. Outside Windows there was no
 * roaming store, so only the marker gets written. Fail-open: any failure leaves the old files where
 * they are, and the marker is then absent too, so the attempt repeats on the next launch.
 *
 * Used by [dev.iustitia.replay.ClipStore] (`.iusclip`) and [dev.iustitia.config.PresetManager]
 * (preset `.json`) — the two stores that hold EXPLICIT user exports rather than session state.
 */
object LegacyStoreMigration {

    /** Presence of this file in the target dir means the one-time scan has already run. */
    private const val MARKER = ".legacy-migrated"

    /** Copy [keep]-matching files from the legacy roaming store's [legacySubdir] into [target], once.
     *  [keep] receives the bare file name. Never throws; individual files that fail to copy are
     *  skipped. */
    fun migrate(legacySubdir: String, target: Path, keep: (String) -> Boolean) {
        try {
            Files.createDirectories(target)
            val marker = target.resolve(MARKER)
            if (Files.exists(marker)) return

            val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
            if (!appdata.isNullOrBlank()) {
                val legacy = Path.of(appdata).resolve(".iustitia").resolve(legacySubdir)
                if (legacy.toAbsolutePath() != target.toAbsolutePath() && Files.isDirectory(legacy)) {
                    Files.list(legacy).use { stream ->
                        stream.filter { keep(it.fileName.toString()) }.forEach { src ->
                            try {
                                val dst = target.resolve(src.fileName.toString())
                                if (!Files.exists(dst)) Files.copy(src, dst)
                            } catch (_: Throwable) {
                                // Skip this file (raced by a concurrent copy, unreadable, ...).
                            }
                        }
                    }
                }
            }

            Files.createFile(marker)
        } catch (_: Throwable) {
            // Fail-open: legacy files stay in place, un-migrated (and unmarked, so we retry next launch).
        }
    }
}
