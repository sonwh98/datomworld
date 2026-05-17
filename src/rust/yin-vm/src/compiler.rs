use im::{HashMap, Vector, vector};
use crate::value::Value;

pub fn compile(form: Value) -> Value {
    compile_form(form, true, &HashMap::new(), &initial_macro_env())
}

pub fn initial_macro_env() -> HashMap<Value, Value> {
    let mut env = HashMap::new();
    env.insert(Value::symbol("defmacro"), Value::Map(HashMap::new())); // Bootstrap marker
    env
}

pub fn compile_form(
    form: Value,
    tail: bool,
    _env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    match form {
        Value::Vector(v) if !v.is_empty() => {
            let operator = v.get(0).unwrap();
            let operands = v.iter().skip(1).cloned().collect::<Vec<_>>();

            if let Value::Symbol(s) = operator {
                match s.as_ref() {
                    "fn" => compile_lambda(&v, tail, _env, macro_env),
                    "if" => compile_if(&v, tail, _env, macro_env),
                    "let" => compile_let(&v, tail, _env, macro_env),
                    "do" => compile_do(&v, tail, _env, macro_env),
                    "quote" => compile_quote(&v, tail),
                    "def" => compile_def(&v, tail, _env, macro_env),
                    "defn" => compile_defn(&v, tail, _env, macro_env),
                    "and" => compile_and(&v, tail, _env, macro_env),
                    "or" => compile_or(&v, tail, _env, macro_env),
                    "dao.stream.apply/call" => compile_dao_call(&v, tail, _env, macro_env),
                    _ if macro_env.contains_key(operator) => {
                        compile_macro_expand(&v, tail, _env, macro_env)
                    }
                    _ => compile_application(operator.clone(), &operands, tail, _env, macro_env),
                }
            } else {
                compile_application(operator.clone(), &operands, tail, _env, macro_env)
            }
        }
        Value::Symbol(s) => {
            let mut node = HashMap::new();
            node.insert(Value::keyword("type"), Value::keyword("variable"));
            node.insert(Value::keyword("name"), Value::Symbol(s));
            Value::Map(node)
        }
        _ => {
            let mut node = HashMap::new();
            node.insert(Value::keyword("type"), Value::keyword("literal"));
            node.insert(Value::keyword("value"), form);
            Value::Map(node)
        }
    }
}

fn compile_application(
    operator: Value,
    operands: &[Value],
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("application"));
    node.insert(Value::keyword("operator"), compile_form(operator, false, env, macro_env));
    
    let compiled_operands = operands.iter()
        .map(|o| compile_form(o.clone(), false, env, macro_env))
        .collect::<Vector<_>>();
    node.insert(Value::keyword("operands"), Value::Vector(compiled_operands));
    
    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_lambda(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let params = form.get(1).expect("fn requires params");
    let body_forms = form.iter().skip(2).cloned().collect::<Vec<_>>();
    
    let body_expr = if body_forms.len() == 1 {
        body_forms[0].clone()
    } else {
        let mut do_form = vector![Value::symbol("do")];
        for b in body_forms { do_form.push_back(b); }
        Value::Vector(do_form)
    };

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("lambda"));
    node.insert(Value::keyword("params"), params.clone());
    node.insert(Value::keyword("body"), compile_form(body_expr, true, env, macro_env));
    
    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_if(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let test = form.get(1).expect("if requires test");
    let cons = form.get(2).expect("if requires consequent");
    let alt = form.get(3).cloned().unwrap_or(Value::Nil);

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("if"));
    node.insert(Value::keyword("test"), compile_form(test.clone(), false, env, macro_env));
    node.insert(Value::keyword("consequent"), compile_form(cons.clone(), tail, env, macro_env));
    node.insert(Value::keyword("alternate"), compile_form(alt, tail, env, macro_env));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_let(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let bindings = match form.get(1) {
        Some(Value::Vector(v)) => v,
        _ => panic!("let requires binding vector"),
    };
    let body_forms = form.iter().skip(2).cloned().collect::<Vec<_>>();
    let body_expr = if body_forms.len() == 1 {
        body_forms[0].clone()
    } else {
        let mut do_form = vector![Value::symbol("do")];
        for b in body_forms { do_form.push_back(b); }
        Value::Vector(do_form)
    };

    expand_let(bindings, body_expr, tail, env, macro_env)
}

fn expand_let(
    bindings: &Vector<Value>,
    body: Value,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    if bindings.is_empty() {
        return compile_form(body, tail, env, macro_env);
    }

    let name = bindings.get(0).expect("binding name missing");
    let val = bindings.get(1).expect("binding value missing");
    let rest = bindings.iter().skip(2).cloned().collect::<Vector<_>>();

    let compiled_val = compile_form(val.clone(), false, env, macro_env);
    let inner_body_uat = expand_let(&rest, body, true, env, macro_env);

    let mut lambda_node = HashMap::new();
    lambda_node.insert(Value::keyword("type"), Value::keyword("lambda"));
    lambda_node.insert(Value::keyword("params"), Value::Vector(vector![name.clone()]));
    lambda_node.insert(Value::keyword("body"), inner_body_uat);

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("application"));
    node.insert(Value::keyword("operator"), Value::Map(lambda_node));
    node.insert(Value::keyword("operands"), Value::Vector(vector![compiled_val]));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_do(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let exprs = form.iter().skip(1).cloned().collect::<Vec<_>>();
    if exprs.is_empty() {
        let mut node = HashMap::new();
        node.insert(Value::keyword("type"), Value::keyword("literal"));
        node.insert(Value::keyword("value"), Value::Nil);
        return Value::Map(node);
    }
    if exprs.len() == 1 {
        return compile_form(exprs[0].clone(), tail, env, macro_env);
    }

    let first = exprs[0].clone();
    let rest_exprs = exprs.iter().skip(1).cloned().collect::<Vec<_>>();
    let mut rest_do = vector![Value::symbol("do")];
    for e in rest_exprs { rest_do.push_back(e); }

    let compiled_first = compile_form(first, false, env, macro_env);
    let compiled_rest = compile_form(Value::Vector(rest_do), true, env, macro_env);

    let mut lambda_node = HashMap::new();
    lambda_node.insert(Value::keyword("type"), Value::keyword("lambda"));
    lambda_node.insert(Value::keyword("params"), Value::Vector(vector![Value::symbol("_")]));
    lambda_node.insert(Value::keyword("body"), compiled_rest);

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("application"));
    node.insert(Value::keyword("operator"), Value::Map(lambda_node));
    node.insert(Value::keyword("operands"), Value::Vector(vector![compiled_first]));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_quote(form: &Vector<Value>, _tail: bool) -> Value {
    let val = form.get(1).cloned().unwrap_or(Value::Nil);
    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("literal"));
    node.insert(Value::keyword("value"), val);
    Value::Map(node)
}

fn compile_def(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let sym = form.get(1).expect("def requires symbol");
    let val = form.get(2).expect("def requires value");

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("application"));
    
    let mut op = HashMap::new();
    op.insert(Value::keyword("type"), Value::keyword("variable"));
    op.insert(Value::keyword("name"), Value::symbol("yin/def"));
    node.insert(Value::keyword("operator"), Value::Map(op));

    let mut sym_node = HashMap::new();
    sym_node.insert(Value::keyword("type"), Value::keyword("literal"));
    sym_node.insert(Value::keyword("value"), sym.clone());
    
    let compiled_val = compile_form(val.clone(), false, env, macro_env);
    node.insert(Value::keyword("operands"), Value::Vector(vector![Value::Map(sym_node), compiled_val]));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_defn(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    // (defn name [params] body...)
    let name = form.get(1).expect("defn requires name");
    let params = form.get(2).expect("defn requires params");
    let body_forms = form.iter().skip(3).cloned().collect::<Vec<_>>();

    let body_expr = if body_forms.len() == 1 {
        body_forms[0].clone()
    } else {
        let mut do_form = vector![Value::symbol("do")];
        for b in body_forms { do_form.push_back(b); }
        Value::Vector(do_form)
    };

    let mut lambda_vec = vector![Value::symbol("fn"), params.clone()];
    lambda_vec.push_back(body_expr);

    let def_form = vector![Value::symbol("def"), name.clone(), Value::Vector(lambda_vec)];
    compile_form(Value::Vector(def_form), tail, env, macro_env)
}

fn compile_and(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let operands = form.iter().skip(1).cloned().collect::<Vec<_>>();
    if operands.is_empty() {
        let mut node = HashMap::new();
        node.insert(Value::keyword("type"), Value::keyword("literal"));
        node.insert(Value::keyword("value"), Value::Boolean(true));
        return Value::Map(node);
    }
    if operands.len() == 1 {
        return compile_form(operands[0].clone(), tail, env, macro_env);
    }

    let x = operands[0].clone();
    let rest = operands.iter().skip(1).cloned().collect::<Vec<_>>();
    let mut and_rest = vector![Value::symbol("and")];
    for r in rest { and_rest.push_back(r); }

    let if_form = vector![Value::symbol("if"), x.clone(), Value::Vector(and_rest), x];
    compile_form(Value::Vector(if_form), tail, env, macro_env)
}

fn compile_or(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let operands = form.iter().skip(1).cloned().collect::<Vec<_>>();
    if operands.is_empty() {
        let mut node = HashMap::new();
        node.insert(Value::keyword("type"), Value::keyword("literal"));
        node.insert(Value::keyword("value"), Value::Nil);
        return Value::Map(node);
    }
    if operands.len() == 1 {
        return compile_form(operands[0].clone(), tail, env, macro_env);
    }

    let x = operands[0].clone();
    let rest = operands.iter().skip(1).cloned().collect::<Vec<_>>();
    let mut or_rest = vector![Value::symbol("or")];
    for r in rest { or_rest.push_back(r); }

    let g = Value::symbol("or__g");
    let if_form = vector![Value::symbol("if"), g.clone(), g.clone(), Value::Vector(or_rest)];
    let let_form = vector![Value::symbol("let"), Value::Vector(vector![g, x]), Value::Vector(if_form)];
    compile_form(Value::Vector(let_form), tail, env, macro_env)
}

fn compile_dao_call(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let op = form.get(1).expect("dao.stream.apply/call requires op");
    let operands = form.iter().skip(2).cloned().collect::<Vec<_>>();

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("dao.stream.apply/call"));
    node.insert(Value::keyword("op"), op.clone());
    
    let compiled_operands = operands.iter()
        .map(|o| compile_form(o.clone(), false, env, macro_env))
        .collect::<Vector<_>>();
    node.insert(Value::keyword("operands"), Value::Vector(compiled_operands));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn compile_macro_expand(
    form: &Vector<Value>,
    tail: bool,
    env: &HashMap<Value, Value>,
    macro_env: &HashMap<Value, Value>,
) -> Value {
    let operator = v_get(form, 0);
    let operands = form.iter().skip(1).cloned().collect::<Vec<_>>();

    let macro_info = macro_env.get(&operator).unwrap();
    let operator_ast = if let Value::Map(m) = macro_info {
        m.get(&Value::keyword("lambda-ast")).cloned().unwrap_or_else(|| {
            let mut v = HashMap::new();
            v.insert(Value::keyword("type"), Value::keyword("variable"));
            v.insert(Value::keyword("name"), operator.clone());
            Value::Map(v)
        })
    } else {
        let mut v = HashMap::new();
        v.insert(Value::keyword("type"), Value::keyword("variable"));
        v.insert(Value::keyword("name"), operator.clone());
        Value::Map(v)
    };

    let mut node = HashMap::new();
    node.insert(Value::keyword("type"), Value::keyword("yin/macro-expand"));
    node.insert(Value::keyword("operator"), operator_ast);
    
    let compiled_operands = operands.iter()
        .map(|o| compile_form(o.clone(), false, env, macro_env))
        .collect::<Vector<_>>();
    node.insert(Value::keyword("operands"), Value::Vector(compiled_operands));

    if tail {
        node.insert(Value::keyword("tail?"), Value::Boolean(true));
    }
    Value::Map(node)
}

fn v_get(v: &Vector<Value>, idx: usize) -> Value {
    v.get(idx).cloned().unwrap_or(Value::Nil)
}
