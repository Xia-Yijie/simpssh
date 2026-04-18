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
                // lineHeight 等于 fontSize 才能让字形在 size 盒子里居中;
                // 默认 lineHeight 会额外加上 ascent/descent 让字形偏离中心。
                lineHeight = fontSize,
            ),
        )
    }
}

object NerdGlyphs {
    const val FOLDER         = "\uF07B"
    const val FOLDER_OPEN    = "\uF07C"
    const val FILE           = "\uF15B"
    const val FOLDER_PLUS    = "\uEEC7"

    const val ARROW_LEFT     = "\uF060"
    const val CHEVRON_UP     = "\uF077"
    const val CHEVRON_DOWN   = "\uF078"
    const val CHEVRON_RIGHT  = "\uF054"
    const val HOME           = "\uF015"
    const val TIMES          = "\uF00D"

    const val PLUS           = "\uF067"
    const val TRASH          = "\uF1F8"
    const val EDIT           = "\uF044"
    const val PENCIL         = "\uF040"
    const val PLAY           = "\uF04B"
    const val REFRESH        = "\uF021"
    const val UPLOAD         = "\uF093"
    const val DOWNLOAD       = "\uF019"
    const val ELLIPSIS_V     = "\uF142"
    const val SEARCH         = "\uF002"

    const val TERMINAL       = "\uF120"
    const val CLOUD          = "\uF0C2"
    const val HELP           = "\uF059"
    const val INFO           = "\uF05A"
    const val CODE           = "\uF121"
    const val COG            = "\uF013"
    const val KEYBOARD       = "\uF11C"
    const val MOUSE_POINTER  = "\uF245"
    const val HAND_POINTER   = "\uF25A"
    const val HAND_UP        = "\uF0A6"
    const val HAND_DOWN      = "\uF0A7"
    const val HAND_LEFT      = "\uF0A5"
    const val HAND_RIGHT     = "\uF0A4"
    const val ARROWS_V       = "\uF07D"
    const val ARROWS_H       = "\uF07E"
    const val COMPRESS       = "\uF066"
    const val EXPAND         = "\uF065"
    const val FILE_TEXT      = "\uF15C"
    const val FILE_IMAGE     = "\uF1C5"
    const val FILE_VIDEO     = "\uF1C8"
    const val FILE_AUDIO     = "\uF1C7"
    const val COPY           = "\uF0C5"

    // 借用 fa-align-left 作为自动换行开关图标(Nerd Font 没有专门的 wrap 字形)。
    const val WRAP_TEXT      = "\uF036"
}

fun glyphForFile(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) {
        return when (name.lowercase()) {
            ".gitignore", ".gitattributes" -> "\uE702"
            "dockerfile" -> "\uE650"
            "makefile" -> "\uE673"
            "license" -> NerdGlyphs.FILE_TEXT
            "readme" -> "\uE609"
            else -> NerdGlyphs.FILE
        }
    }
    return when (ext) {
        "py", "pyw", "pyi" -> "\uE606"
        "rs" -> "\uE68B"
        "js", "mjs", "cjs" -> "\uE60C"
        "jsx" -> "\uE7BA"
        "ts" -> "\uE628"
        "tsx" -> "\uE7BA"
        "go" -> "\uE627"
        "java", "class", "jar" -> "\uE66D"
        "kt", "kts" -> "\uE634"
        "c" -> "\uE649"
        "cpp", "cc", "cxx", "c++" -> "\uE646"
        "h", "hpp", "hh" -> "\uE645"
        "rb" -> "\uE605"
        "swift" -> "\uE699"
        "lua" -> "\uE620"
        "php" -> "\uE73D"
        "scala", "sc" -> "\uE737"
        "cs" -> "\uE648"
        "vue" -> "\uE6A0"
        "dart" -> "\uE798"
        "r" -> "\uE68A"
        "jl" -> "\uE624"
        "ex", "exs" -> "\uE62D"

        "md", "markdown" -> "\uE609"
        "rst", "txt", "log" -> NerdGlyphs.FILE_TEXT
        "tex" -> "\uE69B"

        "html", "htm", "xhtml" -> "\uE60E"
        "css" -> "\uE614"
        "scss", "sass" -> "\uE603"
        "less" -> "\uE60B"

        "json", "json5", "jsonl" -> "\uE60B"
        "yaml", "yml" -> "\uE6A8"
        "toml" -> "\uE6B2"
        // seti-xml 在 BMP 之外,这里用 mdi-xml 作为 BMP 回退。
        "xml" -> "\uF72D"
        "ini", "conf", "cfg", "properties" -> "\uE615"
        "csv", "tsv" -> "\uE64A"
        "sql" -> "\uE706"
        "db", "sqlite", "sqlite3" -> "\uE7C4"

        "sh", "bash", "zsh", "fish", "ksh" -> "\uE691"

        "dockerfile" -> "\uE650"
        "mk", "makefile" -> "\uE673"

        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff" -> "\uE60D"
        "svg" -> "\uE698"

        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "\uF1C7"
        "mp4", "mov", "mkv", "avi", "webm", "flv" -> "\uF1C8"

        "pdf" -> "\uF1C1"
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar" -> "\uF1C6"
        "doc", "docx" -> "\uF1C2"
        "xls", "xlsx" -> "\uF1C3"
        "ppt", "pptx" -> "\uF1C4"

        "ttf", "otf", "woff", "woff2" -> "\uF031"

        else -> NerdGlyphs.FILE
    }
}
