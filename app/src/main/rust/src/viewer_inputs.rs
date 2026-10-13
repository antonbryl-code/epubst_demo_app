//! Tells documents the viewer's page geometry through `sys.inputs`.
//!
//! The viewer never changes a document's page setup itself. Documents that support reflowing
//! read these inputs (all strings in Typst length syntax, as `typst compile --input` would pass
//! them) and lay themselves out pageless; documents that ignore them render as authored:
//! - `epubst-width`: the width of the view, e.g. `"300pt"`
//! - `epubst-margin`: the margin the viewer suggests on every side, e.g. `"12pt"`
//!
//! For example:
//! ```typ
//! #let conf(doc, paper: "a4", margin: 2.5cm) = {
//!   let width = sys.inputs.at("epubst-width", default: none)
//!   if width == none {
//!     set page(paper: paper, margin: margin)
//!     doc
//!   } else {
//!     set page(width: eval(width), height: auto, margin: eval(sys.inputs.at("epubst-margin")))
//!     doc
//!   }
//! }
//! #show: conf
//! ```

use typst::foundations::{Dict, Str, Value};
use typst::{Library, LibraryExt};

/// Page geometry offered to the document, in points. `f32` as received from Kotlin, so the
/// inputs print as e.g. `"0.1pt"` rather than the widened `"0.10000000149011612pt"`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ViewerGeometry {
    pub width: f32,
    pub margin: f32,
}

/// The standard library with `sys.inputs` describing `geometry`.
pub fn library(geometry: ViewerGeometry) -> Library {
    let mut inputs = Dict::new();
    inputs.insert("epubst-width".into(), length(geometry.width));
    inputs.insert("epubst-margin".into(), length(geometry.margin));
    Library::builder().with_inputs(inputs).build()
}

fn length(pt: f32) -> Value {
    Value::Str(Str::from(format!("{pt}pt")))
}
