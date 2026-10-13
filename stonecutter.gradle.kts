plugins {
    id("dev.kikugie.stonecutter")
}

// The active version owns the working tree: `src/` renders with this version's
// preprocessor conditions while editing. The shared source is written in the
// 26.3 (official-name) shape; 1.21.11 differences are carried in conditions.
stonecutter active "26.3"
