use std::sync::Mutex;

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line, Point};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::{test::TermSize, Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{Color as AnsiColor, NamedColor, Processor};

#[derive(Clone, Copy, Debug, Default)]
struct NoopListener;

impl EventListener for NoopListener {
    fn send_event(&self, _: Event) {}
}

struct Inner {
    term: Term<NoopListener>,
    parser: Processor,
}

#[derive(uniffi::Object)]
pub struct TerminalView {
    inner: Mutex<Inner>,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct CursorPos {
    pub row: u32,
    pub col: u32,
}

// start/len 以 UTF-16 code unit 为单位,以匹配 Java/Kotlin 的字符串索引;
// 跨语言传 UTF-8 byte offset 会在 Kotlin 侧用错导致切字符。
#[derive(Clone, Debug, uniffi::Record)]
pub struct StyleSpan {
    pub start: u32,
    pub len: u32,
    pub fg: u32,
    pub bg: u32,
    pub flags: u32,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct StyledRow {
    pub text: String,
    pub spans: Vec<StyleSpan>,
}

const DEFAULT_FG: u32 = 0xD3D7CF;
const DEFAULT_BG: u32 = 0x000000;

const BOLD: u32      = 1 << 0;
const ITALIC: u32    = 1 << 1;
const UNDERLINE: u32 = 1 << 2;
const INVERSE: u32   = 1 << 3;

#[uniffi::export]
impl TerminalView {
    #[uniffi::constructor]
    pub fn new(columns: u16, rows: u16) -> std::sync::Arc<Self> {
        let size = TermSize::new(columns as usize, rows as usize);
        let term = Term::new(Config::default(), &size, NoopListener);
        std::sync::Arc::new(Self {
            inner: Mutex::new(Inner { term, parser: Processor::new() }),
        })
    }

    pub fn feed(&self, bytes: Vec<u8>) {
        let mut g = self.inner.lock().unwrap();
        let Inner { term, parser } = &mut *g;
        parser.advance(term, &bytes);
    }

    pub fn snapshot(&self) -> Vec<String> {
        let g = self.inner.lock().unwrap();
        let grid = g.term.grid();
        let rows = grid.screen_lines();
        let cols = grid.columns();
        // Grid[Point] 索引的是"实时视口",不是用户当前看到的画面;要看到 scrollback
        // 里的历史行,必须把 display_offset 从行号里减掉,否则一滚上去就全是空行。
        let display_offset = grid.display_offset() as i32;
        let mut out = Vec::with_capacity(rows);
        for row in 0..rows {
            let mut line = String::with_capacity(cols);
            for col in 0..cols {
                let point = Point::new(Line(row as i32 - display_offset), Column(col));
                let cell = &grid[point];
                if is_rendered_cell(cell.flags) {
                    line.push(cell.c);
                }
            }
            while line.ends_with(' ') { line.pop(); }
            out.push(line);
        }
        out
    }

    pub fn snapshot_styled(&self) -> Vec<StyledRow> {
        let g = self.inner.lock().unwrap();
        let grid = g.term.grid();
        let rows = grid.screen_lines();
        let cols = grid.columns();
        let display_offset = grid.display_offset() as i32;
        let mut out = Vec::with_capacity(rows);
        for row in 0..rows {
            let mut text = String::with_capacity(cols);
            let mut spans: Vec<StyleSpan> = Vec::new();
            let mut span_start: u32 = 0;
            let mut offset: u32 = 0;
            let mut cur = (0u32, 0u32, 0u32);
            let mut have = false;
            for col in 0..cols {
                let cell = &grid[Point::new(Line(row as i32 - display_offset), Column(col))];
                if !is_rendered_cell(cell.flags) {
                    continue;
                }
                let raw_fg = resolve_color(cell.fg);
                let raw_bg = resolve_color(cell.bg);
                let flags = map_flags(cell.flags);
                // inverse 在这里就把 fg/bg 交换好,UI 侧不用再判断;否则每个平台都要重复这段逻辑。
                let (fg, bg) = if flags & INVERSE != 0 { (raw_bg, raw_fg) } else { (raw_fg, raw_bg) };
                let style = (fg, bg, flags);
                if have && style != cur {
                    spans.push(StyleSpan {
                        start: span_start, len: offset - span_start,
                        fg: cur.0, bg: cur.1, flags: cur.2,
                    });
                    span_start = offset;
                }
                cur = style;
                have = true;
                text.push(cell.c);
                offset += cell.c.len_utf16() as u32;
            }
            if have {
                spans.push(StyleSpan {
                    start: span_start, len: offset - span_start,
                    fg: cur.0, bg: cur.1, flags: cur.2,
                });
            }
            out.push(StyledRow { text, spans });
        }
        out
    }

    pub fn cursor(&self) -> CursorPos {
        let g = self.inner.lock().unwrap();
        let grid = g.term.grid();
        let p = grid.cursor.point;
        let screen_lines = grid.screen_lines() as i32;
        let visible_row = p.line.0 + grid.display_offset() as i32;
        if visible_row < 0 || visible_row >= screen_lines {
            // 哨兵值:光标已经被滚出当前视口(用户向上翻 scrollback 时),
            // 返回 u32::MAX 让调用方跳过渲染而不是画一个错位的光标。
            return CursorPos { row: u32::MAX, col: 0 };
        }
        let grid_row = p.line.0;
        let max_col = p.column.0;
        let mut col = 0u32;
        for grid_col in 0..max_col {
            let cell = &grid[Point::new(Line(grid_row), Column(grid_col))];
            if is_rendered_cell(cell.flags) {
                col += cell.c.len_utf16() as u32;
            }
        }
        CursorPos { row: visible_row as u32, col }
    }

    // 契约:调用本方法的同时,调用方必须用同样的 cols/rows 调 SshSession::resize
    // 同步远端 PTY;只改一边会让终端状态机和服务端输出永久错位。
    pub fn resize(&self, columns: u16, rows: u16) {
        let mut g = self.inner.lock().unwrap();
        let size = TermSize::new(columns as usize, rows as usize);
        g.term.resize(size);
    }

    // 返回值是性能契约:true 表示 display_offset 实际变了,调用方才需要重跑
    // snapshot_styled + cursor;滚到 scrollback 顶/底被 clamp 时返回 false,
    // 让上层跳过整个快照刷新,避免滚动到边界时还在做无用的渲染工作。
    pub fn scroll_display(&self, delta: i32) -> bool {
        let mut g = self.inner.lock().unwrap();
        let before = g.term.grid().display_offset();
        g.term.scroll_display(Scroll::Delta(delta));
        g.term.grid().display_offset() != before
    }

    pub fn reset_scroll(&self) {
        let mut g = self.inner.lock().unwrap();
        g.term.scroll_display(Scroll::Bottom);
    }

    pub fn is_bracketed_paste(&self) -> bool {
        let g = self.inner.lock().unwrap();
        g.term.mode().contains(TermMode::BRACKETED_PASTE)
    }

    pub fn is_mouse_reporting(&self) -> bool {
        let g = self.inner.lock().unwrap();
        g.term.mode().intersects(TermMode::MOUSE_MODE)
    }

    pub fn is_alt_screen(&self) -> bool {
        let g = self.inner.lock().unwrap();
        g.term.mode().contains(TermMode::ALT_SCREEN)
    }
}

fn resolve_color(c: AnsiColor) -> u32 {
    match c {
        AnsiColor::Named(n) => named_color(n),
        AnsiColor::Spec(rgb) => ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | (rgb.b as u32),
        AnsiColor::Indexed(i) => xterm256(i),
    }
}

fn named_color(n: NamedColor) -> u32 {
    use NamedColor::*;
    match n {
        Black          => 0x000000,
        Red            => 0xCC0000,
        Green          => 0x4E9A06,
        Yellow         => 0xC4A000,
        Blue           => 0x3465A4,
        Magenta        => 0x75507B,
        Cyan           => 0x06989A,
        White          => 0xD3D7CF,
        BrightBlack    => 0x555753,
        BrightRed      => 0xEF2929,
        BrightGreen    => 0x8AE234,
        BrightYellow   => 0xFCE94F,
        BrightBlue     => 0x729FCF,
        BrightMagenta  => 0xAD7FA8,
        BrightCyan     => 0x34E2E2,
        BrightWhite    => 0xEEEEEC,
        Foreground     => DEFAULT_FG,
        Background     => DEFAULT_BG,
        DimBlack       => 0x000000,
        DimRed         => 0x800000,
        DimGreen       => 0x008000,
        DimYellow      => 0x808000,
        DimBlue        => 0x000080,
        DimMagenta     => 0x800080,
        DimCyan        => 0x008080,
        DimWhite       => 0x808080,
        BrightForeground | DimForeground => DEFAULT_FG,
        Cursor         => DEFAULT_FG,
    }
}

fn xterm256(idx: u8) -> u32 {
    if idx < 16 {
        let palette = [
            0x000000, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
            0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
        ];
        palette[idx as usize]
    } else if idx < 232 {
        let i = (idx - 16) as u32;
        let r = i / 36;
        let g = (i / 6) % 6;
        let b = i % 6;
        let comp = |v: u32| if v == 0 { 0 } else { 0x37 + 0x28 * v };
        (comp(r) << 16) | (comp(g) << 8) | comp(b)
    } else {
        let v = 0x08 + 0x0A * ((idx - 232) as u32);
        (v << 16) | (v << 8) | v
    }
}

fn map_flags(f: Flags) -> u32 {
    let mut out = 0u32;
    if f.contains(Flags::BOLD)      { out |= BOLD; }
    if f.contains(Flags::ITALIC)    { out |= ITALIC; }
    if f.contains(Flags::UNDERLINE) { out |= UNDERLINE; }
    if f.contains(Flags::INVERSE)   { out |= INVERSE; }
    out
}

fn is_rendered_cell(flags: Flags) -> bool {
    !flags.intersects(Flags::WIDE_CHAR_SPACER | Flags::LEADING_WIDE_CHAR_SPACER)
}
