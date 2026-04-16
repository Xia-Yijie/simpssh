package com.simpssh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simpssh.R

/// Symbols Nerd Font Mono — 10000+ glyphs covering Devicons, Seti UI,
/// Font Awesome, MDI, Octicons. We render them with [NerdIcon] in place
/// of any Material Icon.
val NerdFontFamily = FontFamily(Font(R.font.nerd_symbols))

@Composable
fun NerdIcon(
    glyph: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = tint,
                fontFamily = NerdFontFamily,
                fontSize = fontSize,
                // lineHeight = fontSize keeps the glyph centered within `size`
                // (default lineHeight adds ascent/descent padding that pushes
                // it off-center).
                lineHeight = fontSize,
            ),
        )
    }
}

/// Glyph codepoints we use directly (UI controls + folder/file fallbacks).
/// All in BMP (no surrogate pairs needed) — taken from Font Awesome / Seti
/// / Devicons subsets of Nerd Fonts.
object NerdGlyphs {
    // Folder / file fallback
    const val FOLDER         = "\uF07B"   // fa-folder
    const val FOLDER_OPEN    = "\uF07C"   // fa-folder-open
    const val FILE           = "\uF15B"   // fa-file
    const val FOLDER_PLUS    = "\uEEC7"   // fa-folder-plus

    // Navigation
    const val ARROW_LEFT     = "\uF060"   // fa-arrow-left
    const val CHEVRON_UP     = "\uF077"   // fa-chevron-up
    const val CHEVRON_DOWN   = "\uF078"   // fa-chevron-down
    const val CHEVRON_RIGHT  = "\uF054"   // fa-chevron-right
    const val HOME           = "\uF015"   // fa-home
    const val TIMES          = "\uF00D"   // fa-times (close)

    // Actions
    const val PLUS           = "\uF067"   // fa-plus
    const val TRASH          = "\uF1F8"   // fa-trash
    const val EDIT           = "\uF044"   // fa-edit
    const val PENCIL         = "\uF040"   // fa-pencil
    const val PLAY           = "\uF04B"   // fa-play
    const val REFRESH        = "\uF021"   // fa-refresh
    const val UPLOAD         = "\uF093"   // fa-upload
    const val DOWNLOAD       = "\uF019"   // fa-download
    const val ELLIPSIS_V     = "\uF142"   // fa-ellipsis-v (more menu)
    const val SEARCH         = "\uF002"

    // Misc UI
    const val TERMINAL       = "\uF120"   // fa-terminal
    const val CLOUD          = "\uF0C2"   // fa-cloud
    const val HELP           = "\uF059"   // fa-question-circle
    const val INFO           = "\uF05A"   // fa-info-circle
    const val CODE           = "\uF121"   // fa-code
}

/// Map a filename to a Nerd Font glyph based on its extension. Falls back to
/// the generic file glyph for unknown extensions.
fun glyphForFile(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) {
        // No extension — special-case a few common dotfiles
        return when (name.lowercase()) {
            ".gitignore", ".gitattributes" -> "\uE702"  // dev-git
            "dockerfile" -> "\uE650"                    // seti-docker
            "makefile" -> "\uE673"                      // seti-makefile
            "license" -> "\uF15C"                       // fa-file-text-o
            "readme" -> "\uE609"                        // seti-markdown
            else -> NerdGlyphs.FILE
        }
    }
    return when (ext) {
        // Programming languages (Seti UI subset for VSCode-like consistency)
        "py", "pyw", "pyi" -> "\uE606"                  // seti-python
        "rs" -> "\uE68B"                                // seti-rust
        "js", "mjs", "cjs" -> "\uE60C"                  // seti-javascript
        "jsx" -> "\uE7BA"                               // dev-react
        "ts" -> "\uE628"                                // seti-typescript
        "tsx" -> "\uE7BA"                               // dev-react
        "go" -> "\uE627"                                // seti-go
        "java", "class", "jar" -> "\uE66D"              // seti-java
        "kt", "kts" -> "\uE634"                         // seti-kotlin
        "c" -> "\uE649"                                 // seti-c
        "cpp", "cc", "cxx", "c++" -> "\uE646"           // seti-cpp
        "h", "hpp", "hh" -> "\uE645"                    // seti-c-h (header)
        "rb" -> "\uE605"                                // seti-ruby
        "swift" -> "\uE699"                             // seti-swift
        "lua" -> "\uE620"                               // dev-lua
        "php" -> "\uE73D"                               // dev-php
        "scala", "sc" -> "\uE737"                       // dev-scala
        "cs" -> "\uE648"                                // seti-c-sharp
        "vue" -> "\uE6A0"                               // seti-vue
        "dart" -> "\uE798"                              // dev-dart
        "r" -> "\uE68A"                                 // seti-r
        "jl" -> "\uE624"                                // seti-julia
        "ex", "exs" -> "\uE62D"                         // seti-elixir

        // Markup / docs
        "md", "markdown" -> "\uE609"                    // seti-markdown
        "rst", "txt", "log" -> "\uF15C"                 // fa-file-text-o
        "tex" -> "\uE69B"                               // seti-tex

        // Web
        "html", "htm", "xhtml" -> "\uE60E"              // seti-html
        "css" -> "\uE614"                               // seti-css
        "scss", "sass" -> "\uE603"                      // seti-sass
        "less" -> "\uE60B"                              // seti-less

        // Data / config
        "json", "json5", "jsonl" -> "\uE60B"            // seti-json
        "yaml", "yml" -> "\uE6A8"                       // seti-yml
        "toml" -> "\uE6B2"                              // seti-config (approx)
        "xml" -> "\uF72D"                               // mdi-xml (BMP fallback)
        "ini", "conf", "cfg", "properties" -> "\uE615"  // seti-config
        "csv", "tsv" -> "\uE64A"                        // seti-csv
        "sql" -> "\uE706"                               // dev-database
        "db", "sqlite", "sqlite3" -> "\uE7C4"           // dev-database alt

        // Shell
        "sh", "bash", "zsh", "fish", "ksh" -> "\uE691"  // seti-shell

        // Containers / build
        "dockerfile" -> "\uE650"                        // seti-docker
        "mk", "makefile" -> "\uE673"                    // seti-makefile

        // Images
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff" -> "\uE60D"  // seti-image
        "svg" -> "\uE698"                               // seti-svg

        // Audio / video
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "\uF1C7"  // fa-file-audio-o
        "mp4", "mov", "mkv", "avi", "webm", "flv" -> "\uF1C8"  // fa-file-video-o

        // Documents / archives
        "pdf" -> "\uF1C1"                               // fa-file-pdf-o
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar" -> "\uF1C6"  // fa-file-archive-o
        "doc", "docx" -> "\uF1C2"                       // fa-file-word-o
        "xls", "xlsx" -> "\uF1C3"                       // fa-file-excel-o
        "ppt", "pptx" -> "\uF1C4"                       // fa-file-powerpoint-o

        // Fonts
        "ttf", "otf", "woff", "woff2" -> "\uF031"       // fa-font

        else -> NerdGlyphs.FILE
    }
}
