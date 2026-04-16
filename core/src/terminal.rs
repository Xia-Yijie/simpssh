use std::sync::Mutex;

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line, Point};
use alacritty_terminal::term::{test::TermSize, Config, Term};
use alacritty_terminal::vte::ansi::Processor;

#[derive(Clone, Copy, Debug, Default)]
struct NoopListener;

impl EventListener for NoopListener {
    fn send_event(&self, _: Event) {}
}

struct Inner {
    term: Term<NoopListener>,
    parser: Processor,
}

/// Headless VT100/xterm terminal. Exposed to Kotlin/Swift as an opaque handle.
#[derive(uniffi::Object)]
pub struct TerminalView {
    inner: Mutex<Inner>,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct CursorPos {
    pub row: u32,
    pub col: u32,
}

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

    /// Visible rows, trailing spaces trimmed, styling dropped (for now).
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

    pub fn cursor(&self) -> CursorPos {
        let g = self.inner.lock().unwrap();
        let p = g.term.grid().cursor.point;
        CursorPos { row: p.line.0 as u32, col: p.column.0 as u32 }
    }
}
