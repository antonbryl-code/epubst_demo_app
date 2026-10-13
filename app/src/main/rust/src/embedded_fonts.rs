//! Typst's default fonts (Libertinus Serif, New Computer Modern and its maths font, DejaVu
//! Sans Mono), embedded deflate-compressed by build.rs to save about 3 MB. Each is
//! decompressed once at startup to read its metadata, and again when a document first uses it.

use std::io::Read;

use typst::foundations::Bytes;
use typst::text::{Font, FontInfo};
use typst_kit::fonts::FontSource;

/// The compressed font files, in typst-assets' order.
static COMPRESSED: &[&[u8]] = include!(concat!(env!("OUT_DIR"), "/fonts.rs"));

/// One face of a compressed embedded font file.
pub struct EmbeddedFont {
    compressed: &'static [u8],
    index: u32,
}

impl FontSource for EmbeddedFont {
    fn load(&self) -> Option<Font> {
        Font::new(Bytes::new(inflate(self.compressed)?), self.index)
    }
}

/// The embedded fonts' faces with their metadata, for a `typst_kit::fonts::FontStore`.
pub fn embedded() -> impl Iterator<Item = (EmbeddedFont, FontInfo)> {
    COMPRESSED.iter().flat_map(|&compressed| {
        // Faces 0, 1, ... until one doesn't parse (the embedded files have a single face each).
        let faces: Vec<_> = inflate(compressed)
            .map(|data| (0..).map_while(|index| Some((index, FontInfo::new(&data, index)?))).collect())
            .unwrap_or_default();
        faces.into_iter().map(move |(index, info)| (EmbeddedFont { compressed, index }, info))
    })
}

fn inflate(compressed: &[u8]) -> Option<Vec<u8>> {
    let mut data = Vec::with_capacity(compressed.len() * 2);
    flate2::read::DeflateDecoder::new(compressed).read_to_end(&mut data).ok()?;
    Some(data)
}
