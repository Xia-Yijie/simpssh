use simpssh_core::TerminalView;

#[test]
fn plain_text_is_echoed_to_grid() {
    let t = TerminalView::new(20, 3);
    t.feed(b"hello".to_vec());
    let rows = t.snapshot();
    assert_eq!(rows[0], "hello");
    let c = t.cursor();
    assert_eq!((c.row, c.col), (0, 5));
}

#[test]
fn csi_cursor_positioning_is_honored() {
    let t = TerminalView::new(20, 3);
    // ESC[2;3H moves cursor to row 2, col 3 (1-based), then print "X".
    t.feed(b"\x1b[2;3HX".to_vec());
    let rows = t.snapshot();
    assert_eq!(rows[1], "  X");
}

#[test]
fn newline_and_wrap() {
    let t = TerminalView::new(5, 3);
    t.feed(b"ab\r\ncd".to_vec());
    let rows = t.snapshot();
    assert_eq!(rows[0], "ab");
    assert_eq!(rows[1], "cd");
}
