package com.simpssh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.CancellationException

/// 统一规格:Highlights 库支持的语言与我们自己的 regex 分词器共用这一形状,
/// 让扩展名映射、下拉选单和分发器都能消费同一类型。
internal class LangSpec(
    val id: String,
    val label: String,
    val highlight: (text: String, darkMode: Boolean) -> AnnotatedString,
) {
    override fun equals(other: Any?) = other is LangSpec && other.id == id
    override fun hashCode() = id.hashCode()
}

private fun highlightViaHighlights(text: String, syntax: SyntaxLanguage, darkMode: Boolean): AnnotatedString {
    val highlights = try {
        Highlights.Builder()
            .code(text)
            .theme(SyntaxThemes.darcula(darkMode = darkMode))
            .language(syntax)
            .build()
            .getHighlights()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        for (h in highlights) {
            val start = h.location.start
            val end = h.location.end
            if (end <= start) continue
            when (h) {
                is ColorHighlight -> addStyle(
                    SpanStyle(color = Color(0xFF000000.toInt() or (h.rgb and 0xFFFFFF))),
                    start, end,
                )
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            }
        }
    }
}

internal data class SyntaxPalette(
    val keyword: Color, val string: Color, val comment: Color,
    val number: Color, val type: Color,
    val tag: Color, val attribute: Color, val value: Color,
    val heading: Color, val link: Color, val codeBg: Color,
) {
    companion object {
        fun of(darkMode: Boolean) = if (darkMode) DARK else LIGHT
    }
}

private val DARK = SyntaxPalette(
    keyword = Color(0xFF569CD6), string = Color(0xFFCE9178),
    comment = Color(0xFF6A9955), number = Color(0xFFB5CEA8),
    type = Color(0xFF4EC9B0),
    tag = Color(0xFF569CD6), attribute = Color(0xFF9CDCFE), value = Color(0xFFCE9178),
    heading = Color(0xFF4FC1FF), link = Color(0xFF3794FF),
    codeBg = Color(0x26888888),
)
private val LIGHT = SyntaxPalette(
    keyword = Color(0xFF0000C0), string = Color(0xFFA31515),
    comment = Color(0xFF008000), number = Color(0xFF098658),
    type = Color(0xFF267F99),
    tag = Color(0xFF800000), attribute = Color(0xFFE45649), value = Color(0xFFA31515),
    heading = Color(0xFF0000C0), link = Color(0xFF0000EE),
    codeBg = Color(0x1A000000),
)

private class Rule(val regex: Regex, val styleOf: (SyntaxPalette) -> SpanStyle)

private fun tokenize(rules: List<Rule>): (String, Boolean) -> AnnotatedString = { text, dark ->
    val p = SyntaxPalette.of(dark)
    buildAnnotatedString {
        append(text)
        for (rule in rules) {
            rule.regex.findAll(text).forEach { m ->
                addStyle(rule.styleOf(p), m.range.first, m.range.last + 1)
            }
        }
    }
}

private val RE_HASH_COMMENT = Regex("#[^\n]*")
private val RE_LINE_COMMENT_DASH = Regex("--[^\n]*")
private val RE_LINE_COMMENT_SLASH = Regex("//[^\n]*")
private val RE_LINE_COMMENT_HASH_OR_SEMI = Regex("[;#][^\n]*")
private val RE_BLOCK_COMMENT_SLASH = Regex("(?s)/\\*.*?\\*/")
private val RE_STRING_DQ = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
private val RE_STRING_DQ_SIMPLE = Regex("\"[^\"\n]*\"")
private val RE_STRING_SQ = Regex("'[^'\n]*'")
private val RE_NUMBER_SIMPLE = Regex("\\b-?\\d+(?:\\.\\d+)?\\b")

private val RE_MD_FENCED = Regex("(?s)```[^\n]*\n.*?```")
private val RE_MD_HEADING = Regex("^#{1,6}[^\n]*", RegexOption.MULTILINE)
private val RE_MD_BLOCKQUOTE = Regex("^>[^\n]*", RegexOption.MULTILINE)
private val RE_MD_LIST = Regex("^[ \t]*[-*+][ \t]", RegexOption.MULTILINE)
private val RE_MD_INLINE_CODE = Regex("`[^`\n]+`")
private val RE_MD_BOLD = Regex("(\\*\\*|__)(?!\\s)[^\n]+?\\1")
private val RE_MD_ITALIC = Regex("(?<![*_\\w])([*_])(?![*_\\s])[^\n*_]+?\\1(?![*_\\w])")
private val RE_MD_LINK = Regex("\\[[^\\]\n]+\\]\\([^)\n]+\\)")

private val RE_JSON_NUMBER = Regex("-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")
private val RE_JSON_LITERAL = Regex("\\b(?:true|false|null)\\b")

private val RE_YAML_KEY = Regex("^[ \t]*[\\w.\\-]+(?=:)", RegexOption.MULTILINE)
private val RE_YAML_ANCHOR = Regex("[&*]\\w+")
private val RE_YAML_LIST_BULLET = Regex("^[ \t]*-[ \t]", RegexOption.MULTILINE)
private val RE_YAML_LITERAL = Regex(
    "\\b(?:true|false|null|yes|no|True|False|Null|Yes|No|TRUE|FALSE|NULL)\\b",
)

private val RE_TOML_SECTION = Regex("^\\[\\[?[^\\]\n]+\\]?\\]", RegexOption.MULTILINE)
private val RE_TOML_KEY = Regex("^[ \t]*[\\w.\\-]+(?=[ \t]*=)", RegexOption.MULTILINE)
private val RE_TOML_LITERAL = Regex("\\b(?:true|false)\\b")

private val RE_INI_SECTION = Regex("^\\[[^\\]\n]+\\]", RegexOption.MULTILINE)
private val RE_INI_KEY = Regex("^[ \t]*[\\w.\\-]+(?=[ \t]*=)", RegexOption.MULTILINE)

private val RE_XML_COMMENT = Regex("(?s)<!--.*?-->")
private val RE_XML_CDATA = Regex("(?s)<!\\[CDATA\\[.*?\\]\\]>")
private val RE_XML_DOCTYPE = Regex("<![^>\n]+>")
private val RE_XML_TAG_OPEN = Regex("</?[\\w:\\-]+")
private val RE_XML_TAG_CLOSE = Regex("/?>")
private val RE_XML_ATTR_NAME = Regex("\\b[\\w:\\-]+(?==)")

private val RE_CSS_AT_RULE = Regex("@[\\w\\-]+")
private val RE_CSS_PROPERTY = Regex("[\\w\\-]+(?=[ \t]*:)")
private val RE_CSS_HEX = Regex("#[0-9a-fA-F]{3,8}\\b")
private val RE_CSS_NUMBER = Regex(
    "-?\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|vmin|vmax|pt|pc|ex|ch|s|ms|deg|rad|turn|fr)?\\b",
)

private val SQL_KEYWORDS: List<String> = listOf(
    "SELECT", "FROM", "WHERE", "JOIN", "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "CROSS", "ON", "USING",
    "GROUP", "ORDER", "BY", "HAVING", "DISTINCT", "UNION", "INTERSECT", "EXCEPT", "ALL", "AS",
    "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TRUNCATE",
    "CREATE", "TABLE", "VIEW", "INDEX", "DROP", "ALTER", "ADD", "RENAME", "COLUMN",
    "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "CONSTRAINT", "UNIQUE", "CHECK", "DEFAULT",
    "NULL", "NOT", "AND", "OR", "IN", "EXISTS", "BETWEEN", "LIKE", "ILIKE", "IS",
    "CASE", "WHEN", "THEN", "ELSE", "END", "IF", "ELSEIF", "WHILE", "DO", "FOR", "LOOP",
    "LIMIT", "OFFSET", "RETURNING", "WITH", "RECURSIVE", "CTE",
    "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "SAVEPOINT",
    "TRUE", "FALSE", "ASC", "DESC",
    "INT", "INTEGER", "BIGINT", "SMALLINT", "NUMERIC", "DECIMAL",
    "REAL", "DOUBLE", "FLOAT", "CHAR", "VARCHAR", "TEXT", "BLOB",
    "DATE", "TIME", "TIMESTAMP", "BOOLEAN",
)
private val RE_SQL_STRING = Regex("'(?:[^'\\\\\n]|\\\\.)*'")
private val RE_SQL_KEYWORDS = Regex("\\b(?:${SQL_KEYWORDS.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

private val CMAKE_KEYWORDS: List<String> = listOf(
    "if", "elseif", "else", "endif",
    "foreach", "endforeach",
    "while", "endwhile",
    "function", "endfunction",
    "macro", "endmacro",
    "break", "continue", "return",
    "cmake_minimum_required", "project", "cmake_policy",
    "add_executable", "add_library", "add_custom_target", "add_custom_command",
    "target_link_libraries", "target_include_directories", "target_compile_options",
    "target_compile_definitions", "target_compile_features", "target_sources",
    "set", "unset", "list", "string", "math", "file",
    "get_property", "set_property", "set_target_properties", "get_target_property",
    "include", "include_directories", "link_directories", "link_libraries",
    "find_package", "find_library", "find_path", "find_program", "find_file",
    "add_subdirectory", "install", "export",
    "enable_testing", "add_test",
    "message", "option", "configure_file",
)
private val RE_CMAKE_KEYWORDS = Regex(
    "\\b(?:${CMAKE_KEYWORDS.joinToString("|")})\\b",
    RegexOption.IGNORE_CASE,
)
private val RE_CMAKE_VARIABLE = Regex("\\$(?:ENV|CACHE)?\\{[A-Za-z_][A-Za-z0-9_]*\\}")

private val MARKDOWN_RULES = listOf(
    // 围栏代码块放在最前:后续的标题/加粗等规则可能会叠加在其上,
    // 这是相对于完整 Markdown 解析器的一个可接受的视觉妥协。
    Rule(RE_MD_FENCED) { SpanStyle(background = it.codeBg) },
    Rule(RE_MD_HEADING) { SpanStyle(color = it.heading, fontWeight = FontWeight.Bold) },
    Rule(RE_MD_BLOCKQUOTE) { SpanStyle(color = it.comment, fontStyle = FontStyle.Italic) },
    Rule(RE_MD_LIST) { SpanStyle(color = it.keyword) },
    Rule(RE_MD_INLINE_CODE) { SpanStyle(background = it.codeBg) },
    Rule(RE_MD_BOLD) { SpanStyle(fontWeight = FontWeight.Bold) },
    Rule(RE_MD_ITALIC) { SpanStyle(fontStyle = FontStyle.Italic) },
    Rule(RE_MD_LINK) { SpanStyle(color = it.link, textDecoration = TextDecoration.Underline) },
)

private val JSON_RULES = listOf(
    Rule(RE_LINE_COMMENT_SLASH) { SpanStyle(color = it.comment) },
    Rule(RE_BLOCK_COMMENT_SLASH) { SpanStyle(color = it.comment) },
    Rule(RE_STRING_DQ) { SpanStyle(color = it.string) },
    Rule(RE_JSON_NUMBER) { SpanStyle(color = it.number) },
    Rule(RE_JSON_LITERAL) { SpanStyle(color = it.keyword) },
)

private val YAML_RULES = listOf(
    Rule(RE_HASH_COMMENT) { SpanStyle(color = it.comment) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.string) },
    Rule(RE_STRING_SQ) { SpanStyle(color = it.string) },
    Rule(RE_YAML_KEY) { SpanStyle(color = it.attribute) },
    Rule(RE_NUMBER_SIMPLE) { SpanStyle(color = it.number) },
    Rule(RE_YAML_LITERAL) { SpanStyle(color = it.keyword) },
    Rule(RE_YAML_ANCHOR) { SpanStyle(color = it.type) },
    Rule(RE_YAML_LIST_BULLET) { SpanStyle(color = it.keyword) },
)

private val TOML_RULES = listOf(
    Rule(RE_HASH_COMMENT) { SpanStyle(color = it.comment) },
    Rule(RE_TOML_SECTION) { SpanStyle(color = it.heading, fontWeight = FontWeight.Bold) },
    Rule(RE_TOML_KEY) { SpanStyle(color = it.attribute) },
    Rule(RE_STRING_DQ) { SpanStyle(color = it.string) },
    Rule(RE_STRING_SQ) { SpanStyle(color = it.string) },
    Rule(RE_NUMBER_SIMPLE) { SpanStyle(color = it.number) },
    Rule(RE_TOML_LITERAL) { SpanStyle(color = it.keyword) },
)

private val INI_RULES = listOf(
    Rule(RE_LINE_COMMENT_HASH_OR_SEMI) { SpanStyle(color = it.comment) },
    Rule(RE_INI_SECTION) { SpanStyle(color = it.heading, fontWeight = FontWeight.Bold) },
    Rule(RE_INI_KEY) { SpanStyle(color = it.attribute) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.string) },
)

private val XML_RULES = listOf(
    Rule(RE_XML_COMMENT) { SpanStyle(color = it.comment) },
    Rule(RE_XML_CDATA) { SpanStyle(color = it.string) },
    Rule(RE_XML_DOCTYPE) { SpanStyle(color = it.keyword) },
    Rule(RE_XML_TAG_OPEN) { SpanStyle(color = it.tag) },
    Rule(RE_XML_TAG_CLOSE) { SpanStyle(color = it.tag) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.value) },
    Rule(RE_STRING_SQ) { SpanStyle(color = it.value) },
    Rule(RE_XML_ATTR_NAME) { SpanStyle(color = it.attribute) },
)

private val CSS_RULES = listOf(
    Rule(RE_BLOCK_COMMENT_SLASH) { SpanStyle(color = it.comment) },
    // 单行注释属于 SCSS / LESS 方言,严格 CSS 并不支持,这里一并处理。
    Rule(RE_LINE_COMMENT_SLASH) { SpanStyle(color = it.comment) },
    Rule(RE_CSS_AT_RULE) { SpanStyle(color = it.keyword) },
    Rule(RE_CSS_PROPERTY) { SpanStyle(color = it.attribute) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.string) },
    Rule(RE_STRING_SQ) { SpanStyle(color = it.string) },
    Rule(RE_CSS_HEX) { SpanStyle(color = it.number) },
    Rule(RE_CSS_NUMBER) { SpanStyle(color = it.number) },
)

private val CMAKE_RULES = listOf(
    Rule(RE_HASH_COMMENT) { SpanStyle(color = it.comment) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.string) },
    Rule(RE_CMAKE_VARIABLE) { SpanStyle(color = it.attribute) },
    Rule(RE_CMAKE_KEYWORDS) { SpanStyle(color = it.keyword, fontWeight = FontWeight.SemiBold) },
    Rule(RE_NUMBER_SIMPLE) { SpanStyle(color = it.number) },
)

private val SQL_RULES = listOf(
    Rule(RE_LINE_COMMENT_DASH) { SpanStyle(color = it.comment) },
    Rule(RE_BLOCK_COMMENT_SLASH) { SpanStyle(color = it.comment) },
    Rule(RE_SQL_STRING) { SpanStyle(color = it.string) },
    Rule(RE_STRING_DQ_SIMPLE) { SpanStyle(color = it.string) },
    Rule(RE_SQL_KEYWORDS) { SpanStyle(color = it.keyword, fontWeight = FontWeight.SemiBold) },
    Rule(RE_NUMBER_SIMPLE) { SpanStyle(color = it.number) },
)

internal val LANG_PLAIN = LangSpec("plain", "纯文本") { text, _ -> AnnotatedString(text) }

private fun hl(id: String, label: String, syntax: SyntaxLanguage) = LangSpec(id, label) { text, darkMode ->
    highlightViaHighlights(text, syntax, darkMode)
}

internal val LANG_KOTLIN = hl("kotlin", "Kotlin", SyntaxLanguage.KOTLIN)
internal val LANG_JAVA = hl("java", "Java", SyntaxLanguage.JAVA)
internal val LANG_PYTHON = hl("python", "Python", SyntaxLanguage.PYTHON)
internal val LANG_JAVASCRIPT = hl("javascript", "JavaScript", SyntaxLanguage.JAVASCRIPT)
internal val LANG_TYPESCRIPT = hl("typescript", "TypeScript", SyntaxLanguage.TYPESCRIPT)
internal val LANG_COFFEESCRIPT = hl("coffeescript", "CoffeeScript", SyntaxLanguage.COFFEESCRIPT)
internal val LANG_RUST = hl("rust", "Rust", SyntaxLanguage.RUST)
internal val LANG_GO = hl("go", "Go", SyntaxLanguage.GO)
internal val LANG_SWIFT = hl("swift", "Swift", SyntaxLanguage.SWIFT)
internal val LANG_DART = hl("dart", "Dart", SyntaxLanguage.DART)
internal val LANG_C = hl("c", "C", SyntaxLanguage.C)
internal val LANG_CPP = hl("cpp", "C++", SyntaxLanguage.CPP)
/// CUDA 本质上是 C++ 加上 kernel 启动语法和 device 限定符,
/// 复用 CPP 语法对浏览代码来说已经足够。
internal val LANG_CUDA = hl("cuda", "CUDA (C++)", SyntaxLanguage.CPP)
internal val LANG_CSHARP = hl("csharp", "C#", SyntaxLanguage.CSHARP)
internal val LANG_PHP = hl("php", "PHP", SyntaxLanguage.PHP)
internal val LANG_RUBY = hl("ruby", "Ruby", SyntaxLanguage.RUBY)
internal val LANG_PERL = hl("perl", "Perl", SyntaxLanguage.PERL)
internal val LANG_SHELL = hl("shell", "Shell", SyntaxLanguage.SHELL)

internal val LANG_MARKDOWN = LangSpec("markdown", "Markdown", tokenize(MARKDOWN_RULES))
internal val LANG_JSON = LangSpec("json", "JSON", tokenize(JSON_RULES))
internal val LANG_YAML = LangSpec("yaml", "YAML", tokenize(YAML_RULES))
internal val LANG_TOML = LangSpec("toml", "TOML", tokenize(TOML_RULES))
internal val LANG_INI = LangSpec("ini", "INI", tokenize(INI_RULES))
internal val LANG_XML = LangSpec("xml", "XML / HTML", tokenize(XML_RULES))
internal val LANG_CSS = LangSpec("css", "CSS", tokenize(CSS_RULES))
internal val LANG_SQL = LangSpec("sql", "SQL", tokenize(SQL_RULES))
internal val LANG_CMAKE = LangSpec("cmake", "CMake", tokenize(CMAKE_RULES))

internal val CODE_LANGUAGE_BY_EXT: Map<String, LangSpec> = mapOf(
    "kt" to LANG_KOTLIN, "kts" to LANG_KOTLIN,
    "java" to LANG_JAVA,
    "py" to LANG_PYTHON, "pyw" to LANG_PYTHON, "pyi" to LANG_PYTHON,
    "js" to LANG_JAVASCRIPT, "mjs" to LANG_JAVASCRIPT, "cjs" to LANG_JAVASCRIPT, "jsx" to LANG_JAVASCRIPT,
    "ts" to LANG_TYPESCRIPT, "tsx" to LANG_TYPESCRIPT,
    "coffee" to LANG_COFFEESCRIPT,
    "rs" to LANG_RUST,
    "go" to LANG_GO,
    "swift" to LANG_SWIFT,
    "dart" to LANG_DART,
    "c" to LANG_C, "h" to LANG_C,
    "cpp" to LANG_CPP, "cc" to LANG_CPP, "cxx" to LANG_CPP, "c++" to LANG_CPP,
    "hpp" to LANG_CPP, "hh" to LANG_CPP, "hxx" to LANG_CPP,
    "cu" to LANG_CUDA, "cuh" to LANG_CUDA, "cuda" to LANG_CUDA,
    "cs" to LANG_CSHARP,
    "php" to LANG_PHP,
    "rb" to LANG_RUBY,
    "pl" to LANG_PERL,
    "sh" to LANG_SHELL, "bash" to LANG_SHELL, "zsh" to LANG_SHELL, "fish" to LANG_SHELL, "ksh" to LANG_SHELL,
    "md" to LANG_MARKDOWN, "markdown" to LANG_MARKDOWN, "mdx" to LANG_MARKDOWN,
    "json" to LANG_JSON, "json5" to LANG_JSON, "jsonl" to LANG_JSON, "ndjson" to LANG_JSON,
    "yaml" to LANG_YAML, "yml" to LANG_YAML,
    "toml" to LANG_TOML,
    "ini" to LANG_INI, "conf" to LANG_INI, "cfg" to LANG_INI, "properties" to LANG_INI,
    "xml" to LANG_XML, "html" to LANG_XML, "htm" to LANG_XML, "xhtml" to LANG_XML, "svg" to LANG_XML,
    "css" to LANG_CSS, "scss" to LANG_CSS, "less" to LANG_CSS,
    "sql" to LANG_SQL,
    "cmake" to LANG_CMAKE,
)

/// 某些文件靠完整文件名而非扩展名判定语言(例如 CMakeLists.txt 的 .txt 毫无意义)。
/// 匹配顺序:先用这张表,再回落到 [CODE_LANGUAGE_BY_EXT]。
internal val CODE_LANGUAGE_BY_FILENAME: Map<String, LangSpec> = mapOf(
    "cmakelists.txt" to LANG_CMAKE,
)

/// PLAIN 放在列表末尾,作为其他语言都不合适时的兜底选项。
internal val PICKER_LANGUAGES: List<LangSpec> = listOf(
    LANG_KOTLIN, LANG_JAVA, LANG_PYTHON,
    LANG_JAVASCRIPT, LANG_TYPESCRIPT, LANG_COFFEESCRIPT,
    LANG_RUST, LANG_GO, LANG_SWIFT, LANG_DART,
    LANG_C, LANG_CPP, LANG_CUDA, LANG_CSHARP,
    LANG_PHP, LANG_RUBY, LANG_PERL, LANG_SHELL,
    LANG_MARKDOWN,
    LANG_JSON, LANG_YAML, LANG_TOML, LANG_INI,
    LANG_XML, LANG_CSS, LANG_SQL, LANG_CMAKE,
    LANG_PLAIN,
)
