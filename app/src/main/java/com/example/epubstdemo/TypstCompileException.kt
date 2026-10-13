package com.example.epubstdemo

/** Typst reported errors; the message lists them as `path:line:col: message` lines. */
class TypstCompileException(message: String) : Exception(message)
