//! An invisible, selectable copy of a page's text, so text can be selected and copied in the
//! viewer (typst-svg draws glyphs as outlines, which a browser can't select).
//!
//! The layer is HTML laid over the page's SVG. (SVG `<text>` would be simpler, but Android's
//! WebView misplaces the selection handles for it.) Each line is an absolutely positioned
//! element holding the line's words as inline boxes sized to exactly the width Typst laid each
//! word out at, their monospace stand-in text letter-spaced to fill that width, so selections
//! line up with the visible glyphs without any JavaScript. Lengths are in `var(--u)`, one Typst point at the page's
//! current CSS size (see DocumentHtml.kt), so the layer scales with the page.
//!
//! Each line ends in a `<br>`, so copied text keeps the document's lines; the inline boxes
//! within a line are joined without separators.
//!
//! The layer sits on top of the page, so it repeats the page's URL links above its text;
//! otherwise taps on linked text would land on the invisible text instead of the links below.

use std::fmt::Write;

use typst::layout::{Frame, FrameItem, Point, Size, Transform};
use typst::model::Destination;
use typst::text::TextItem;

/// Where the baseline sits below the top of a line box, in em. Only affects how well the
/// highlight's vertical extent matches the glyphs, as the stand-in font's ascent varies.
const BASELINE: f64 = 0.8;

/// The advance of every character in the stand-in font, CSS `monospace`, in em. With it known,
/// each word gets a letter-spacing that makes it exactly as wide as Typst laid it out, so its
/// caret positions (and thus the selection handles) stay within the word.
const MONO_ADVANCE: f64 = 0.6;

/// One text run, positioned on the page.
struct Run {
    /// Maps the run's own coordinates (origin on its baseline, at its start) to the page's.
    ts: Transform,
    width: f64,
    size: f64,
    /// The run's text split into words and whitespace, each with its laid-out width in pt.
    segments: Vec<(String, f64)>,
}

impl Run {
    /// Whether the run is merely translated: these are laid out in lines; others (rotated,
    /// scaled, skewed) each get their own line element with a transform.
    fn is_upright(&self) -> bool {
        self.ts.sx.get() == 1.0 && self.ts.sy.get() == 1.0 && self.ts.kx.get() == 0.0 && self.ts.ky.get() == 0.0
    }

    fn x(&self) -> f64 {
        self.ts.tx.to_pt()
    }

    fn y(&self) -> f64 {
        self.ts.ty.to_pt()
    }

    /// Whether `next` continues this run's line: same baseline, and not starting before this
    /// run ends (which would mean a new column or an overlay).
    fn continues_with(&self, next: &Run) -> bool {
        const TOLERANCE: f64 = 0.5; // pt
        self.is_upright()
            && next.is_upright()
            && (next.y() - self.y()).abs() < TOLERANCE
            && next.x() >= self.x() + self.width - TOLERANCE
    }
}

/// A URL link area, positioned on the page.
struct Link {
    ts: Transform,
    size: Size,
    url: String,
}

/// Returns the page's text layer as HTML, to be placed over the page's SVG.
pub fn render(page: &Frame) -> String {
    let mut runs = Vec::new();
    let mut links = Vec::new();
    collect(page, Transform::identity(), &mut runs, &mut links);

    let mut out = String::new();
    let mut i = 0;
    while i < runs.len() {
        let first = &runs[i];
        let mut end = i;
        while end + 1 < runs.len() && runs[end].continues_with(&runs[end + 1]) {
            end += 1;
        }
        let line = &runs[i..=end];
        let size = line.iter().map(|run| run.size).fold(0.0, f64::max);
        write!(
            out,
            r#"<div class="l" style="left:{};top:{};height:{}"#,
            u(first.x()),
            u(first.y() - BASELINE * size),
            u(size)
        )
        .ok();
        if !first.is_upright() {
            // Rotate/scale/skew around the run's origin on its baseline, like the glyphs.
            let ts = first.ts;
            write!(
                out,
                ";transform-origin:0 {};transform:matrix({},{},{},{},0,0)",
                u(BASELINE * size),
                ts.sx.get(),
                ts.ky.get(),
                ts.kx.get(),
                ts.sy.get(),
            )
            .ok();
        }
        out.push_str(r#"">"#);
        let mut x = first.x();
        for run in line {
            if run.x() - x > 0.01 {
                // The visual gap between runs (e.g. around differently styled text).
                write!(out, r#"<span style="width:{}"></span>"#, u(run.x() - x)).ok();
            }
            write!(out, r#"<span class="r" style="font-size:{};height:{}">"#, u(run.size), u(run.size)).ok();
            for (text, width) in &run.segments {
                let chars = text.chars().count().max(1) as f64;
                let spacing = width / chars - MONO_ADVANCE * run.size;
                write!(out, r#"<span style="width:{};letter-spacing:{}">"#, u(*width), u(spacing)).ok();
                escape(&mut out, text);
                out.push_str("</span>");
            }
            out.push_str("</span>");
            x = run.x() + run.width;
        }
        // Copying doesn't break lines between absolutely positioned elements, but always at a
        // <br>. (The empty line it adds below the text is invisible.)
        out.push_str("<br></div>");
        i = end + 1;
    }
    for link in &links {
        let ts = link.ts;
        out.push_str(r#"<a class="k" href=""#);
        escape(&mut out, &link.url);
        write!(
            out,
            r#"" style="left:{};top:{};width:{};height:{};transform:matrix({},{},{},{},0,0)"></a>"#,
            u(ts.tx.to_pt()),
            u(ts.ty.to_pt()),
            u(link.size.x.to_pt()),
            u(link.size.y.to_pt()),
            ts.sx.get(),
            ts.ky.get(),
            ts.kx.get(),
            ts.sy.get(),
        )
        .ok();
    }
    out
}

/// A length of `pt` Typst points in CSS, scaled with the page.
fn u(pt: f64) -> String {
    format!("calc({:.3}*var(--u))", pt)
}

/// Walks the frame like typst-svg does, collecting text runs and URL links with their page
/// transforms.
fn collect(frame: &Frame, ts: Transform, runs: &mut Vec<Run>, links: &mut Vec<Link>) {
    for (pos, item) in frame.items() {
        let ts = ts.pre_concat(translate(*pos));
        match item {
            FrameItem::Group(group) => collect(&group.frame, ts.pre_concat(group.transform), runs, links),
            FrameItem::Text(text) => {
                if let Some(run) = run(text, ts) {
                    runs.push(run);
                }
            }
            // typst-svg only links URLs, too; links within the document aren't supported.
            FrameItem::Link(Destination::Url(url), size) => {
                links.push(Link { ts, size: *size, url: url.as_str().to_owned() });
            }
            _ => {}
        }
    }
}

fn run(item: &TextItem, ts: Transform) -> Option<Run> {
    let width = item.width().to_pt();
    if item.text.is_empty() || !(width > 0.0) {
        return None;
    }
    Some(Run { ts, width, size: item.size.to_pt(), segments: segments(item) })
}

/// Splits the run into words and whitespace with their widths. Glyphs map to text through
/// their byte ranges; glyphs sharing a range (a cluster) are counted once for the text and
/// with all their advances for the width.
fn segments(item: &TextItem) -> Vec<(String, f64)> {
    let mut out: Vec<(String, f64)> = Vec::new();
    let mut last_range = None;
    for glyph in &item.glyphs {
        let advance = glyph.x_advance.at(item.size).to_pt();
        let range = glyph.range.start as usize..glyph.range.end as usize;
        let text = if last_range.as_ref() == Some(&range) {
            "" // another glyph of the same cluster
        } else {
            item.text.get(range.clone()).unwrap_or("")
        };
        last_range = Some(range);
        let space = !text.is_empty() && text.chars().all(char::is_whitespace);
        match out.last_mut() {
            // Extend the current word, or the current whitespace, with this cluster.
            Some((current, width))
                if text.is_empty() || (current.chars().all(char::is_whitespace) == space) =>
            {
                current.push_str(text);
                *width += advance;
            }
            _ => out.push((text.to_owned(), advance)),
        }
    }
    out
}

fn translate(pos: Point) -> Transform {
    Transform::translate(pos.x, pos.y)
}

/// Escapes text for HTML content and double-quoted attributes.
fn escape(out: &mut String, text: &str) {
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            // Never meaningful to copy; and invalid in XHTML-ish consumers.
            c if c.is_control() && c != '\t' => {}
            c => out.push(c),
        }
    }
}
