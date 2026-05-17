use crate::value::Value;
use crate::yang::python::parser::{PyAst, PyLiteral};
use im::{HashMap, Vector, vector};

pub fn compile(ast: &PyAst) -> Value {
    compile_program(ast, true)
}

fn compile_program(ast: &PyAst, tail: bool) -> Value {
    match ast {
        PyAst::Suite(stmts) => {
            if stmts.is_empty() {
                let mut node = HashMap::new();
                node.insert(Value::keyword("type"), Value::keyword("literal"));
                node.insert(Value::keyword("value"), Value::Nil);
                let mut val = Value::Map(node);
                if tail {
                    if let Value::Map(ref mut m) = val {
                        m.insert(Value::keyword("tail?"), Value::Boolean(true));
                    }
                }
                return val;
            }

            let head = &stmts[0];
            let rest = &stmts[1..];

            if let PyAst::Def { name, params, body } = head {
                let mut z_op = HashMap::new();
                z_op.insert(Value::keyword("type"), Value::keyword("application"));
                z_op.insert(Value::keyword("operator"), z_combinator());
                
                let mut inner_lambda = HashMap::new();
                inner_lambda.insert(Value::keyword("type"), Value::keyword("lambda"));
                inner_lambda.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol(name)]));
                
                let mut body_lambda = HashMap::new();
                body_lambda.insert(Value::keyword("type"), Value::keyword("lambda"));
                let p_vec = params.iter().map(|p| Value::symbol(p)).collect::<Vector<_>>();
                body_lambda.insert(Value::keyword("params"), Value::Vector(p_vec));
                body_lambda.insert(Value::keyword("body"), compile_suite(body, true));
                
                inner_lambda.insert(Value::keyword("body"), Value::Map(body_lambda));
                z_op.insert(Value::keyword("operands"), Value::Vector(vector![Value::Map(inner_lambda)]));

                if rest.is_empty() {
                    let mut node = Value::Map(z_op);
                    if tail {
                        if let Value::Map(ref mut m) = node {
                            m.insert(Value::keyword("tail?"), Value::Boolean(true));
                        }
                    }
                    return node;
                }

                let mut outer_lambda = HashMap::new();
                outer_lambda.insert(Value::keyword("type"), Value::keyword("lambda"));
                outer_lambda.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol(name)]));
                outer_lambda.insert(Value::keyword("body"), compile_program(&PyAst::Suite(rest.to_vec()), tail));

                let mut app = HashMap::new();
                app.insert(Value::keyword("type"), Value::keyword("application"));
                app.insert(Value::keyword("operator"), Value::Map(outer_lambda));
                app.insert(Value::keyword("operands"), Value::Vector(vector![Value::Map(z_op)]));
                if tail {
                    app.insert(Value::keyword("tail?"), Value::Boolean(true));
                }
                Value::Map(app)
            } else if rest.is_empty() {
                compile_stmt(head, tail)
            } else {
                let mut outer_lambda = HashMap::new();
                outer_lambda.insert(Value::keyword("type"), Value::keyword("lambda"));
                outer_lambda.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("_")]));
                outer_lambda.insert(Value::keyword("body"), compile_program(&PyAst::Suite(rest.to_vec()), tail));

                let mut app = HashMap::new();
                app.insert(Value::keyword("type"), Value::keyword("application"));
                app.insert(Value::keyword("operator"), Value::Map(outer_lambda));
                app.insert(Value::keyword("operands"), Value::Vector(vector![compile_stmt(head, false)]));
                if tail {
                    app.insert(Value::keyword("tail?"), Value::Boolean(true));
                }
                Value::Map(app)
            }
        }
        _ => compile_stmt(ast, tail),
    }
}

fn compile_suite(ast: &PyAst, tail: bool) -> Value {
    match ast {
        PyAst::Suite(stmts) => {
            if stmts.len() == 1 {
                compile_stmt(&stmts[0], tail)
            } else {
                let head = &stmts[0];
                let rest = &stmts[1..];
                
                let mut outer_lambda = HashMap::new();
                outer_lambda.insert(Value::keyword("type"), Value::keyword("lambda"));
                outer_lambda.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("_")]));
                outer_lambda.insert(Value::keyword("body"), compile_suite(&PyAst::Suite(rest.to_vec()), tail));

                let mut app = HashMap::new();
                app.insert(Value::keyword("type"), Value::keyword("application"));
                app.insert(Value::keyword("operator"), Value::Map(outer_lambda));
                app.insert(Value::keyword("operands"), Value::Vector(vector![compile_stmt(head, false)]));
                if tail {
                    app.insert(Value::keyword("tail?"), Value::Boolean(true));
                }
                Value::Map(app)
            }
        }
        _ => compile_stmt(ast, tail),
    }
}

fn compile_stmt(node: &PyAst, tail: bool) -> Value {
    match node {
        PyAst::Literal(lit) => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("literal"));
            let val = match lit {
                PyLiteral::Number(n, true) => Value::Float(ordered_float::OrderedFloat(*n)),
                PyLiteral::Number(n, false) => Value::Integer(*n as i64),
                PyLiteral::String(s) => Value::String(std::sync::Arc::from(s.as_str())),
                PyLiteral::Boolean(b) => Value::Boolean(*b),
                PyLiteral::Nil => Value::Nil,
            };
            m.insert(Value::keyword("value"), val);
            Value::Map(m)
        }
        PyAst::Variable(name) => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("variable"));
            m.insert(Value::keyword("name"), Value::symbol(name));
            Value::Map(m)
        }
        PyAst::BinOp { op, left, right } => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("application"));
            let mut operator = HashMap::new();
            operator.insert(Value::keyword("type"), Value::keyword("variable"));
            operator.insert(Value::keyword("name"), Value::symbol(op));
            m.insert(Value::keyword("operator"), Value::Map(operator));
            m.insert(
                Value::keyword("operands"),
                Value::Vector(vector![compile_stmt(left, false), compile_stmt(right, false)]),
            );
            if tail {
                m.insert(Value::keyword("tail?"), Value::Boolean(true));
            }
            Value::Map(m)
        }
        PyAst::Call { function, args } => {
            if let PyAst::Variable(name) = &**function {
                if name == "ffi.call" || name == "dao.stream.apply.call" {
                    return compile_ffi_call(args, tail);
                }
            }
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("application"));
            m.insert(Value::keyword("operator"), compile_stmt(function, false));
            let compiled_args = args.iter().map(|a| compile_stmt(a, false)).collect::<Vector<_>>();
            m.insert(Value::keyword("operands"), Value::Vector(compiled_args));
            if tail {
                m.insert(Value::keyword("tail?"), Value::Boolean(true));
            }
            Value::Map(m)
        }
        PyAst::Lambda { params, body } => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("lambda"));
            let p_vec = params.iter().map(|p| Value::symbol(p)).collect::<Vector<_>>();
            m.insert(Value::keyword("params"), Value::Vector(p_vec));
            m.insert(Value::keyword("body"), compile_stmt(body, true));
            if tail {
                m.insert(Value::keyword("tail?"), Value::Boolean(true));
            }
            Value::Map(m)
        }
        PyAst::IfExpr { test, consequent, alternate } => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("if"));
            m.insert(Value::keyword("test"), compile_stmt(test, false));
            m.insert(Value::keyword("consequent"), compile_stmt(consequent, tail));
            m.insert(Value::keyword("alternate"), compile_stmt(alternate, tail));
            if tail {
                m.insert(Value::keyword("tail?"), Value::Boolean(true));
            }
            Value::Map(m)
        }
        PyAst::Return(val) => compile_stmt(val, tail),
        PyAst::IfStmt { test, consequent, alternate } => {
            let mut m = HashMap::new();
            m.insert(Value::keyword("type"), Value::keyword("if"));
            m.insert(Value::keyword("test"), compile_stmt(test, false));
            m.insert(Value::keyword("consequent"), compile_suite(consequent, tail));
            let alt = alternate.as_ref().map(|a| compile_suite(a, tail)).unwrap_or_else(|| {
                let mut n = HashMap::new();
                n.insert(Value::keyword("type"), Value::keyword("literal"));
                n.insert(Value::keyword("value"), Value::Nil);
                Value::Map(n)
            });
            m.insert(Value::keyword("alternate"), alt);
            if tail {
                m.insert(Value::keyword("tail?"), Value::Boolean(true));
            }
            Value::Map(m)
        }
        PyAst::Suite(_) => compile_suite(node, tail),
        PyAst::Def { .. } => panic!("Def should be handled at program/suite level"),
    }
}

fn compile_ffi_call(args: &[PyAst], tail: bool) -> Value {
    let op_node = args.get(0).expect("ffi.call requires op");
    let op_val = if let PyAst::Literal(PyLiteral::String(s)) = op_node {
        s.clone()
    } else {
        panic!("ffi.call op must be string literal");
    };

    let mut m = HashMap::new();
    m.insert(Value::keyword("type"), Value::keyword("dao.stream.apply/call"));
    m.insert(Value::keyword("op"), Value::keyword(&op_val));
    let compiled_operands = args.iter().skip(1).map(|a| compile_stmt(a, false)).collect::<Vector<_>>();
    m.insert(Value::keyword("operands"), Value::Vector(compiled_operands));
    if tail {
        m.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(m)
}

fn z_combinator() -> Value {
    // Z = λf. (λx. f (λv. x x v)) (λx. f (λv. x x v))
    // UAT structure mirror of yang.python.cljc
    
    let xxv = {
        let mut app1 = HashMap::new();
        app1.insert(Value::keyword("type"), Value::keyword("application"));
        let mut x_var = HashMap::new();
        x_var.insert(Value::keyword("type"), Value::keyword("variable"));
        x_var.insert(Value::keyword("name"), Value::symbol("x"));
        app1.insert(Value::keyword("operator"), Value::Map(x_var.clone()));
        app1.insert(Value::keyword("operands"), Value::Vector(vector![Value::Map(x_var)]));
        
        let mut app2 = HashMap::new();
        app2.insert(Value::keyword("type"), Value::keyword("application"));
        app2.insert(Value::keyword("operator"), Value::Map(app1));
        let mut v_var = HashMap::new();
        v_var.insert(Value::keyword("type"), Value::keyword("variable"));
        v_var.insert(Value::keyword("name"), Value::symbol("v"));
        app2.insert(Value::keyword("operands"), Value::Vector(vector![Value::Map(v_var)]));
        Value::Map(app2)
    };

    let lam_v = {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("lambda"));
        m.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("v")]));
        m.insert(Value::keyword("body"), xxv);
        Value::Map(m)
    };

    let f_lam_v = {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("application"));
        let mut f_var = HashMap::new();
        f_var.insert(Value::keyword("type"), Value::keyword("variable"));
        f_var.insert(Value::keyword("name"), Value::symbol("f"));
        m.insert(Value::keyword("operator"), Value::Map(f_var));
        m.insert(Value::keyword("operands"), Value::Vector(vector![lam_v]));
        Value::Map(m)
    };

    let x_lambda = {
        let mut m = HashMap::new();
        m.insert(Value::keyword("type"), Value::keyword("lambda"));
        m.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("x")]));
        m.insert(Value::keyword("body"), f_lam_v);
        Value::Map(m)
    };

    let mut z = HashMap::new();
    z.insert(Value::keyword("type"), Value::keyword("lambda"));
    z.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("f")]));
    
    let mut body = HashMap::new();
    body.insert(Value::keyword("type"), Value::keyword("application"));
    body.insert(Value::keyword("operator"), x_lambda.clone());
    body.insert(Value::keyword("operands"), Value::Vector(vector![x_lambda]));
    
    z.insert(Value::keyword("body"), Value::Map(body));
    Value::Map(z)
}
