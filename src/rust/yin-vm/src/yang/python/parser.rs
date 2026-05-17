use crate::yang::python::tokenizer::PyToken;

#[derive(Debug, Clone)]
pub enum PyAst {
    Literal(PyLiteral),
    Variable(String),
    BinOp {
        op: String,
        left: Box<PyAst>,
        right: Box<PyAst>,
    },
    Call {
        function: Box<PyAst>,
        args: Vec<PyAst>,
    },
    Lambda {
        params: Vec<String>,
        body: Box<PyAst>,
    },
    IfExpr {
        test: Box<PyAst>,
        consequent: Box<PyAst>,
        alternate: Box<PyAst>,
    },
    Return(Box<PyAst>),
    Def {
        name: String,
        params: Vec<String>,
        body: Box<PyAst>,
    },
    IfStmt {
        test: Box<PyAst>,
        consequent: Box<PyAst>,
        alternate: Option<Box<PyAst>>,
    },
    Suite(Vec<PyAst>),
}

#[derive(Debug, Clone)]
pub enum PyLiteral {
    Number(f64, bool), // value, is_float
    String(String),
    Boolean(bool),
    Nil,
}

pub struct PyParser<'a> {
    tokens: &'a [PyToken],
    pos: usize,
}

impl<'a> PyParser<'a> {
    pub fn new(tokens: &'a [PyToken]) -> Self {
        Self { tokens, pos: 0 }
    }

    fn peek(&self) -> Option<&PyToken> {
        self.tokens.get(self.pos)
    }

    fn next(&mut self) -> Option<&PyToken> {
        let t = self.tokens.get(self.pos);
        if t.is_some() {
            self.pos += 1;
        }
        t
    }

    fn match_token(&mut self, token: &PyToken) -> bool {
        if let Some(t) = self.peek() {
            if t == token {
                self.pos += 1;
                return true;
            }
        }
        false
    }

    fn expect(&mut self, token: PyToken) {
        if !self.match_token(&token) {
            panic!("Expected {:?}, got {:?}", token, self.peek());
        }
    }

    pub fn parse_program(&mut self) -> PyAst {
        let mut stmts = Vec::new();
        while self.pos < self.tokens.len() {
            stmts.push(self.parse_statement());
        }
        PyAst::Suite(stmts)
    }

    fn parse_statement(&mut self) -> PyAst {
        match self.peek() {
            Some(PyToken::Keyword(k)) if k == "return" => {
                self.next();
                let val = self.parse_expr();
                self.expect(PyToken::Newline);
                PyAst::Return(Box::new(val))
            }
            Some(PyToken::Keyword(k)) if k == "def" => {
                self.next();
                let name = if let Some(PyToken::Identifier(id)) = self.next() {
                    id.clone()
                } else {
                    panic!("Expected func name");
                };
                self.expect(PyToken::LParen);
                let params = self.parse_params(PyToken::RParen);
                self.expect(PyToken::Colon);
                let body = self.parse_suite();
                PyAst::Def {
                    name,
                    params,
                    body: Box::new(body),
                }
            }
            Some(PyToken::Keyword(k)) if k == "if" => {
                self.next();
                let test = self.parse_expr();
                self.expect(PyToken::Colon);
                let cons = self.parse_suite();
                let mut alt = None;
                if let Some(PyToken::Keyword(k)) = self.peek() {
                    if k == "else" {
                        self.next();
                        self.expect(PyToken::Colon);
                        alt = Some(Box::new(self.parse_suite()));
                    }
                }
                PyAst::IfStmt {
                    test: Box::new(test),
                    consequent: Box::new(cons),
                    alternate: alt,
                }
            }
            _ => {
                let expr = self.parse_expr();
                self.expect(PyToken::Newline);
                expr
            }
        }
    }

    fn parse_suite(&mut self) -> PyAst {
        if self.match_token(&PyToken::Newline) {
            self.expect(PyToken::Indent);
            let mut stmts = Vec::new();
            loop {
                if let Some(PyToken::Dedent) = self.peek() {
                    self.next();
                    break;
                }
                stmts.push(self.parse_statement());
            }
            PyAst::Suite(stmts)
        } else {
            PyAst::Suite(vec![self.parse_statement()])
        }
    }

    fn parse_params(&mut self, end_token: PyToken) -> Vec<String> {
        let mut params = Vec::new();
        loop {
            if let Some(t) = self.peek() {
                if t == &end_token {
                    self.next();
                    break;
                }
            }
            if let Some(PyToken::Identifier(id)) = self.next() {
                params.push(id.clone());
            } else {
                panic!("Expected param");
            }
            if !self.match_token(&PyToken::Comma) {
                if let Some(t) = self.peek() {
                    if t == &end_token {
                        self.next();
                        break;
                    }
                }
                panic!("Expected , or {:?}", end_token);
            }
        }
        params
    }

    fn parse_expr(&mut self) -> PyAst {
        if let Some(PyToken::Keyword(k)) = self.peek() {
            if k == "lambda" {
                self.next();
                let params = self.parse_params(PyToken::Colon);
                let body = self.parse_expr();
                return PyAst::Lambda {
                    params,
                    body: Box::new(body),
                };
            }
        }

        let mut ast = self.parse_binary_op(0);
        if let Some(PyToken::Keyword(k)) = self.peek() {
            if k == "if" {
                self.next();
                let test = self.parse_expr();
                self.expect(PyToken::Keyword("else".to_string()));
                let alt = self.parse_expr();
                ast = PyAst::IfExpr {
                    test: Box::new(test),
                    consequent: Box::new(ast),
                    alternate: Box::new(alt),
                };
            }
        }
        ast
    }

    fn parse_binary_op(&mut self, min_prec: i32) -> PyAst {
        let mut left = self.parse_unary();
        let precedence = [
            ("or", 0),
            ("and", 0),
            ("==", 1),
            ("!=", 1),
            ("<", 1),
            (">", 1),
            ("<=", 1),
            (">=", 1),
            ("+", 2),
            ("-", 2),
            ("*", 3),
            ("/", 3),
        ];

        loop {
            let mut found_op = None;
            if let Some(t) = self.peek() {
                let op_str = match t {
                    PyToken::Operator(o) => Some(o.as_str()),
                    PyToken::Keyword(k) if k == "and" || k == "or" => Some(k.as_str()),
                    _ => None,
                };

                if let Some(s) = op_str {
                    if let Some(&(_, prec)) = precedence.iter().find(|(op, _)| *op == s) {
                        if prec >= min_prec {
                            found_op = Some((s.to_string(), prec));
                        }
                    }
                }
            }

            if let Some((op, prec)) = found_op {
                self.next();
                let right = self.parse_binary_op(prec + 1);
                left = PyAst::BinOp {
                    op,
                    left: Box::new(left),
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        left
    }

    fn parse_unary(&mut self) -> PyAst {
        if let Some(PyToken::Operator(o)) = self.peek() {
            if o == "-" {
                self.next();
                let right = self.parse_unary();
                if let PyAst::Literal(PyLiteral::Number(n, is_f)) = right {
                    return PyAst::Literal(PyLiteral::Number(-n, is_f));
                }
                return PyAst::BinOp {
                    op: "-".to_string(),
                    left: Box::new(PyAst::Literal(PyLiteral::Number(0.0, false))),
                    right: Box::new(right),
                };
            }
        }
        self.parse_call()
    }

    fn parse_call(&mut self) -> PyAst {
        let mut ast = self.parse_primary();
        loop {
            if self.match_token(&PyToken::LParen) {
                let mut args = Vec::new();
                if !self.match_token(&PyToken::RParen) {
                    loop {
                        args.push(self.parse_expr());
                        if self.match_token(&PyToken::RParen) {
                            break;
                        }
                        self.expect(PyToken::Comma);
                    }
                }
                ast = PyAst::Call {
                    function: Box::new(ast),
                    args,
                };
            } else {
                break;
            }
        }
        ast
    }

    fn parse_primary(&mut self) -> PyAst {
        match self.next().cloned() {
            Some(PyToken::Number(n)) => {
                if n.contains('.') {
                    PyAst::Literal(PyLiteral::Number(n.parse().unwrap(), true))
                } else {
                    PyAst::Literal(PyLiteral::Number(n.parse().unwrap(), false))
                }
            }
            Some(PyToken::String(s)) => PyAst::Literal(PyLiteral::String(s)),
            Some(PyToken::Keyword(k)) => match k.as_str() {
                "True" => PyAst::Literal(PyLiteral::Boolean(true)),
                "False" => PyAst::Literal(PyLiteral::Boolean(false)),
                "None" => PyAst::Literal(PyLiteral::Nil),
                _ => panic!("Unexpected keyword in primary: {}", k),
            },
            Some(PyToken::Identifier(id)) => PyAst::Variable(id),
            Some(PyToken::LParen) => {
                let ast = self.parse_expr();
                self.expect(PyToken::RParen);
                ast
            }
            t => panic!("Expected primary, got {:?}", t),
        }
    }
}
