pub mod compiler;
pub mod parser;
pub mod tokenizer;

use crate::value::Value;

pub fn compile(source: &str) -> Value {
    let tokens = tokenizer::tokenize(source);
    let mut parser = parser::PyParser::new(&tokens);
    let ast = parser.parse_program();
    compiler::compile(&ast)
}
