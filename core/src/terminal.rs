use std::sync::Mutex;

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line, Point};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::{test::TermSize, Config, Term};
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

/// One run of consecutive cells sharing the same fg/bg/flags. Offsets are
/// in UTF-16 code units, matching Java/Kotlin string indexing.
#[derive(Clone, Debug, uniffi::Record)]
pub struct StyleSpan {
    pub start: u32,
    pub len: u32,
    pub fg: u32,    // 0xRRGGBB
    pub bg: u32,    // 0xRRGGBB
    /// bit 0 = bold, 1 = italic, 2 = underline, 3 = inverse (fg/bg already swapped)
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

    /// Plain-text snapshot, kept for any consumer that doesn't want styling.
    /// Trims trailing spaces per row.
    pub fn snapshot(&self) -> Vec<String> {
        let g = self.inner.lock().unwrap();
        let grid = g.term.grid();
        let rows = grid.screen_lines();
        let cols = grid.columns();
        let mut out = Vec::with_capacity(rows);
        for row in 0..rows {
            let mut line = String::with_capacity(cols);
            for col in 0..cols {
                let point = Point::new(Line(row as i32), Column(col));
                line.push(grid[point].c);
            }
            while line.ends_with(' ') { line.pop(); }
            out.push(line);
        }
        out
    }

    /// Snapshot with per-cell colour and style, run-length encoded into spans
    /// over each row's `text`. Use this to render a coloured terminal in the UI.
    pub fn snapshot_styled(&self) -> Vec<StyledRow> {
        let g = self.inner.lock().unwrap();
        let grid = g.term.grid();
        let rows = grid.screen_lines();
        let cols = grid.columns();
        let mut out = Vec::with_capacity(rows);
        for row in 0..rows {
            let mut text = String::with_capacity(cols);
            let mut spans: Vec<StyleSpan> = Vec::new();
            let mut span_start: u32 = 0;
            let mut offset: u32 = 0;
            let mut cur = (0u32, 0u32, 0u32);
            let mut have = false;
            for col in 0..cols {
                let cell = &grid[Point::new(Line(row as i32), Column(col))];
                let raw_fg = resolve_color(cell.fg, DEFAULT_FG);
                let raw_bg = resolve_color(cell.bg, DEFAULT_BG);
                let flags = map_flags(cell.flags);
                // Apply inverse here so the UI never has to think about it.
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
        let p = g.term.grid().cursor.point;
        CursorPos { row: p.line.0 as u32, col: p.column.0 as u32 }
    }

    /// Resize the terminal grid. Caller should also resize the remote PTY
    /// (via SshSession::resize) so the shell knows the new dimensions.
    pub fn resize(&self, columns: u16, rows: u16) {
        let mut g = self.inner.lock().unwrap();
        let size = TermSize::new(columns as usize, rows as usize);
        g.term.resize(size);
    }
}

fn resolve_color(c: AnsiColor, _default: u32) -> u32 {
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
