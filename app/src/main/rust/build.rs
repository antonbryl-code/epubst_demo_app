//! Embeds Typst's default fonts (Libertinus Serif, New Computer Modern, DejaVu Sans Mono)
//! deflate-compressed, which saves about a third of their 9.5 MB in the library; see
//! src/fonts.rs for the runtime side.

use std::env;
use std::fmt::Write as _;
use std::fs;
use std::io::Write as _;
use std::path::PathBuf;

use flate2::write::DeflateEncoder;
use flate2::Compression;

fn main() {
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let mut list = String::from("&[\n");
    for (i, data) in typst_assets::fonts().enumerate() {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::best());
        encoder.write_all(data).unwrap();
        let name = format!("font{i}.deflate");
        fs::write(out.join(&name), encoder.finish().unwrap()).unwrap();
        writeln!(list, "    include_bytes!(concat!(env!(\"OUT_DIR\"), \"/{name}\")) as &[u8],").unwrap();
    }
    list.push(']');
    fs::write(out.join("fonts.rs"), list).unwrap();
}
