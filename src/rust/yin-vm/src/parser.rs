use std::sync::Arc;
use im::{HashMap, Vector};
use crate::value::Value;
use ordered_float::OrderedFloat;

#[derive(Debug, PartialEq)]
pub enum Token {
    LParen,
    RParen,
    LBracket,
    RBracket,
    LBrace,
    RBrace,
    Quote,
    Keyword(String),
    Symbol(String),
    String(String),
    Integer(i64),
    Float(f64),
    Boolean(bool),
    Nil,
}

pub fn tokenize(input: &str) -> Vec<Token> {
    let mut tokens = Vec::new();
    let mut chars = input.chars().peekable();

    while let Some(&ch) = chars.peek() {
        match ch {
            '(' => { tokens.push(Token::LParen); chars.next(); }
            ')' => { tokens.push(Token::RParen); chars.next(); }
            '[' => { tokens.push(Token::LBracket); chars.next(); }
            ']' => { tokens.push(Token::RBracket); chars.next(); }
            '{' => { tokens.push(Token::LBrace); chars.next(); }
            '}' => { tokens.push(Token::RBrace); chars.next(); }
            '\'' => { tokens.push(Token::Quote); chars.next(); }
            ';' => {
                while let Some(&c) = chars.peek() {
                    if c == '\n' { break; }
                    chars.next();
                }
            }
            '"' => {
                chars.next();
                let mut s = String::new();
                while let Some(&c) = chars.peek() {
                    if c == '"' { chars.next(); break; }
                    if c == '\\' {
                        chars.next();
                        if let Some(nc) = chars.next() {
                            match nc {
                                'n' => s.push('\n'),
                                'r' => s.push('\r'),
                                't' => s.push('\t'),
                                _ => s.push(nc),
                            }
                        }
                    } else {
                        s.push(chars.next().unwrap());
                    }
                }
                tokens.push(Token::String(s));
            }
            c if c.is_whitespace() => { chars.next(); }
            c if c.is_digit(10) || (c == '-' && chars.clone().nth(1).map_or(false, |next| next.is_digit(10))) => {
                let mut s = String::new();
                while let Some(&c) = chars.peek() {
                    if c.is_digit(10) || c == '.' || c == '-' {
                        s.push(chars.next().unwrap());
                    } else {
                        break;
                    }
                }
                if s.contains('.') {
                    if let Ok(f) = s.parse::<f64>() {
                        tokens.push(Token::Float(f));
                    }
                } else if let Ok(i) = s.parse::<i64>() {
                    tokens.push(Token::Integer(i));
                }
            }
            ':' => {
                chars.next();
                let mut s = String::new();
                while let Some(&c) = chars.peek() {
                    if c.is_whitespace() || "()[]{}'\"".contains(c) { break; }
                    s.push(chars.next().unwrap());
                }
                tokens.push(Token::Keyword(s));
            }
            _ => {
                let mut s = String::new();
                while let Some(&c) = chars.peek() {
                    if c.is_whitespace() || "()[]{}'\"".contains(c) { break; }
                    s.push(chars.next().unwrap());
                }
                match s.as_str() {
                    "true" => tokens.push(Token::Boolean(true)),
                    "false" => tokens.push(Token::Boolean(false)),
                    "nil" => tokens.push(Token::Nil),
                    _ => tokens.push(Token::Symbol(s)),
                }
            }
        }
    }
    tokens
}

pub fn parse(tokens: &[Token]) -> Result<(Value, &[Token]), String> {
    if tokens.is_empty() {
        return Err("Unexpected end of input".to_string());
    }

    match &tokens[0] {
        Token::Integer(i) => Ok((Value::Integer(*i), &tokens[1..])),
        Token::Float(f) => Ok((Value::Float(OrderedFloat(*f)), &tokens[1..])),
        Token::String(s) => Ok((Value::String(Arc::from(s.as_str())), &tokens[1..])),
        Token::Keyword(s) => Ok((Value::Keyword(Arc::from(s.as_str())), &tokens[1..])),
        Token::Symbol(s) => Ok((Value::Symbol(Arc::from(s.as_str())), &tokens[1..])),
        Token::Boolean(b) => Ok((Value::Boolean(*b), &tokens[1..])),
        Token::Nil => Ok((Value::Nil, &tokens[1..])),
        Token::Quote => {
            let (val, rest) = parse(&tokens[1..])?;
            let mut list = Vector::new();
            list.push_back(Value::symbol("quote"));
            list.push_back(val);
            // Representing list as vector for now in Value enum
            Ok((Value::Vector(list), rest))
        }
        Token::LBracket => {
            let mut vec = Vector::new();
            let mut rest = &tokens[1..];
            while !rest.is_empty() && rest[0] != Token::RBracket {
                let (val, next_rest) = parse(rest)?;
                vec.push_back(val);
                rest = next_rest;
            }
            if rest.is_empty() {
                return Err("Unclosed bracket".to_string());
            }
            Ok((Value::Vector(vec), &rest[1..]))
        }
        Token::LParen => {
            let mut list = Vector::new();
            let mut rest = &tokens[1..];
            while !rest.is_empty() && rest[0] != Token::RParen {
                let (val, next_rest) = parse(rest)?;
                list.push_back(val);
                rest = next_rest;
            }
            if rest.is_empty() {
                return Err("Unclosed parenthesis".to_string());
            }
            // Representing list as vector for now
            Ok((Value::Vector(list), &rest[1..]))
        }
        Token::LBrace => {
            let mut map = HashMap::new();
            let mut rest = &tokens[1..];
            while !rest.is_empty() && rest[0] != Token::RBrace {
                let (key, next_rest) = parse(rest)?;
                if next_rest.is_empty() || next_rest[0] == Token::RBrace {
                    return Err("Map must have even number of elements".to_string());
                }
                let (val, final_rest) = parse(next_rest)?;
                map.insert(key, val);
                rest = final_rest;
            }
            if rest.is_empty() {
                return Err("Unclosed brace".to_string());
            }
            Ok((Value::Map(map), &rest[1..]))
        }
        _ => Err(format!("Unexpected token: {:?}", tokens[0])),
    }
}

pub fn parse_all(input: &str) -> Result<Vec<Value>, String> {
    let tokens = tokenize(input);
    let mut values = Vec::new();
    let mut rest = &tokens[..];
    while !rest.is_empty() {
        let (val, next_rest) = parse(rest)?;
        values.push(val);
        rest = next_rest;
    }
    Ok(values)
}
