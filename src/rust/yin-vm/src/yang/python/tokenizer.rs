use regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PyToken {
    Number(String),
    String(String),
    Keyword(String),
    Identifier(String),
    Operator(String),
    LParen,
    RParen,
    Comma,
    Colon,
    Indent,
    Dedent,
    Newline,
}

struct TokenPattern {
    token_type: PyTokenType,
    regex: Regex,
}

#[derive(Clone, Copy)]
enum PyTokenType {
    Number,
    StringDouble,
    StringSingle,
    Keyword,
    Identifier,
    Operator,
    LParen,
    RParen,
    Comma,
    Colon,
    Whitespace,
}

fn patterns() -> &'static Vec<TokenPattern> {
    static PATTERNS: OnceLock<Vec<TokenPattern>> = OnceLock::new();
    PATTERNS.get_or_init(|| {
        vec![
            TokenPattern {
                token_type: PyTokenType::Number,
                regex: Regex::new(r"^\d+\.?\d*").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::StringDouble,
                regex: Regex::new(r#"^"([^"]*)""#).unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::StringSingle,
                regex: Regex::new(r"^'([^']*)'").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Keyword,
                regex: Regex::new(r"^(def|lambda|return|if|else|and|or|not|True|False|None)\b")
                    .unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Identifier,
                regex: Regex::new(r"^[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Operator,
                regex: Regex::new(r"^(==|!=|<=|>=|<|>|\+|-|\*|/|=)").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::LParen,
                regex: Regex::new(r"^\(").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::RParen,
                regex: Regex::new(r"^\)").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Comma,
                regex: Regex::new(r"^,").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Colon,
                regex: Regex::new(r"^:").unwrap(),
            },
            TokenPattern {
                token_type: PyTokenType::Whitespace,
                regex: Regex::new(r"^[ \t]+").unwrap(),
            },
        ]
    })
}

fn tokenize_line(line: &str) -> Vec<PyToken> {
    let mut tokens = Vec::new();
    let mut s = line;

    while !s.is_empty() {
        let mut matched = false;
        for pattern in patterns() {
            if let Some(m) = pattern.regex.find(s) {
                let full_match = m.as_str();
                let length = full_match.len();

                match pattern.token_type {
                    PyTokenType::Whitespace => {}
                    PyTokenType::Number => tokens.push(PyToken::Number(full_match.to_string())),
                    PyTokenType::StringDouble | PyTokenType::StringSingle => {
                        let captures = pattern.regex.captures(s).unwrap();
                        tokens.push(PyToken::String(captures.get(1).unwrap().as_str().to_string()));
                    }
                    PyTokenType::Keyword => tokens.push(PyToken::Keyword(full_match.to_string())),
                    PyTokenType::Identifier => {
                        tokens.push(PyToken::Identifier(full_match.to_string()))
                    }
                    PyTokenType::Operator => tokens.push(PyToken::Operator(full_match.to_string())),
                    PyTokenType::LParen => tokens.push(PyToken::LParen),
                    PyTokenType::RParen => tokens.push(PyToken::RParen),
                    PyTokenType::Comma => tokens.push(PyToken::Comma),
                    PyTokenType::Colon => tokens.push(PyToken::Colon),
                }

                s = &s[length..];
                matched = true;
                break;
            }
        }

        if !matched {
            panic!("Tokenization error at: {}", s);
        }
    }
    tokens
}

pub fn tokenize(source: &str) -> Vec<PyToken> {
    let lines = source.lines();
    let mut indents = vec![0];
    let mut tokens = Vec::new();

    for line in lines {
        let trimmed = line.trim_start();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }

        let indent = line.len() - trimmed.len();
        let current_indent = *indents.last().unwrap();

        if indent > current_indent {
            indents.push(indent);
            tokens.push(PyToken::Indent);
        } else if indent < current_indent {
            while let Some(&last) = indents.last() {
                if last > indent {
                    indents.pop();
                    tokens.push(PyToken::Dedent);
                } else {
                    break;
                }
            }
        }

        tokens.extend(tokenize_line(trimmed));
        tokens.push(PyToken::Newline);
    }

    while indents.len() > 1 {
        indents.pop();
        tokens.push(PyToken::Dedent);
    }

    tokens
}
