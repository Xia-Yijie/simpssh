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
    // CSI H 的参数是 1-based:ESC[2;3H 把光标移到第 2 行第 3 列,随后打印 "X"。
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

#[test]
fn wide_chars_do_not_duplicate_in_snapshot_or_cursor() {
    let t = TerminalView::new(10, 3);
    t.feed("你好a".as_bytes().to_vec());
    let rows = t.snapshot();
    assert_eq!(rows[0], "你好a");
    let c = t.cursor();
    assert_eq!((c.row, c.col), (0, 3));
}

#[test]
fn bracketed_paste_mode_tracks_dec_private_2004() {
    let t = TerminalView::new(20, 3);
    assert!(!t.is_bracketed_paste(), "mode off by default");
    t.feed(b"\x1b[?2004h".to_vec());
    assert!(t.is_bracketed_paste(), "mode on after DECSET 2004");
    t.feed(b"\x1b[?2004l".to_vec());
    assert!(!t.is_bracketed_paste(), "mode off after DECRST 2004");
}

#[test]
fn scroll_display_reveals_scrollback() {
    let t = TerminalView::new(5, 3);
    t.feed(b"a\r\nb\r\nc\r\nd\r\ne".to_vec());
    let at_bottom = t.snapshot();
    assert_eq!(at_bottom, vec!["c".to_string(), "d".to_string(), "e".to_string()]);

    t.scroll_display(2);
    let scrolled = t.snapshot();
    assert_eq!(scrolled, vec!["a".to_string(), "b".to_string(), "c".to_string()]);

    t.reset_scroll();
    let back = t.snapshot();
    assert_eq!(back, vec!["c".to_string(), "d".to_string(), "e".to_string()]);
}
