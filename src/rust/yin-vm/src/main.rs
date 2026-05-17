use rustyline::error::ReadlineError;
use rustyline::DefaultEditor;
use yin_vm::{Value, RegisterVM, assemble, parser, compiler};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut rl = DefaultEditor::new()?;
    if rl.load_history("history.txt").is_err() {
        println!("No previous history.");
    }

    let mut vm = RegisterVM::new().with_std_primitives();
    let mut last_values = [Value::Nil, Value::Nil, Value::Nil];
    let mut current_lang = "clojure";
    let mut accumulator = String::new();

    println!("Yin REPL (Rust)");
    println!("Type (help) for commands.");

    loop {
        let prompt = if accumulator.is_empty() {
            format!("yin [{}]> ", current_lang)
        } else {
            let mut indent = " ".repeat(current_lang.len() + 7);
            indent.push_str(".. ");
            indent
        };

        let readline = rl.readline(&prompt);
        match readline {
            Ok(line) => {
                rl.add_history_entry(line.as_str())?;
                accumulator.push_str(&line);
                accumulator.push('\n');

                let input = accumulator.trim();
                if input.is_empty() { 
                    accumulator.clear();
                    continue; 
                }

                if current_lang == "clojure" {
                    let balance = bracket_balance(input);
                    if balance > 0 {
                        continue;
                    }
                    
                    let input_to_eval = accumulator.clone();
                    accumulator.clear();

                    let values = match parser::parse_all(&input_to_eval) {
                        Ok(v) => v,
                        Err(e) => {
                            println!("Error: {}", e);
                            continue;
                        }
                    };

                    for val in values {
                        if is_command(&val) {
                            if let Some(new_lang) = handle_command(&val, &mut vm) {
                                current_lang = new_lang;
                            }
                        } else {
                            let uat = compiler::compile(val);
                            execute_uat(&mut vm, &uat, &mut last_values);
                        }
                    }
                } else if current_lang == "python" {
                    if input.starts_with('(') && input.contains("lang") {
                        let values = match parser::parse_all(input) {
                            Ok(v) => v,
                            Err(e) => {
                                println!("Error: {}", e);
                                accumulator.clear();
                                continue;
                            }
                        };
                        accumulator.clear();
                        for val in values {
                            if is_command(&val) {
                                if let Some(new_lang) = handle_command(&val, &mut vm) {
                                    current_lang = new_lang;
                                }
                            }
                        }
                    } else {
                        // Python multiline detection:
                        let trimmed_line = line.trim();
                        
                        // If this is the start of a new entry
                        if accumulator.len() == line.len() + 1 { 
                            if trimmed_line.ends_with(':') {
                                // Start of a block, keep accumulating
                                continue;
                            }
                            // Single line, evaluate immediately
                        } else {
                            // Already accumulating a block
                            if !trimmed_line.is_empty() {
                                // Still in the block
                                continue;
                            }
                            // Empty line finishes the block
                        }

                        let input_to_eval = accumulator.clone();
                        accumulator.clear();
                        if input_to_eval.trim().is_empty() { continue; }
                        
                        let uat = yin_vm::yang::python::compile(&input_to_eval);
                        execute_uat(&mut vm, &uat, &mut last_values);
                    }
                }
            }
            Err(ReadlineError::Interrupted) => {
                println!("CTRL-C");
                accumulator.clear();
            }
            Err(ReadlineError::Eof) => {
                println!("CTRL-D");
                break;
            }
            Err(err) => {
                println!("Error: {:?}", err);
                break;
            }
        }
    }
    rl.save_history("history.txt")?;
    Ok(())
}

fn is_command(val: &Value) -> bool {
    if let Value::Vector(v) = val {
        if let Some(Value::Symbol(s)) = v.get(0) {
            return matches!(s.as_ref(), "vm" | "lang" | "reset" | "help" | "quit");
        }
    }
    false
}

fn handle_command(val: &Value, vm: &mut RegisterVM) -> Option<&'static str> {
    if let Value::Vector(v) = val {
        if let Some(Value::Symbol(s)) = v.get(0) {
            match s.as_ref() {
                "help" => {
                    println!("Commands:");
                    println!("  (vm :register)           - current VM");
                    println!("  (lang :clojure|:python)  - switch language");
                    println!("  (reset)                  - reset VM state");
                    println!("  (quit)                   - exit REPL");
                    println!("  *1, *2, *3               - last evaluated values");
                    println!("\nEvaluation:");
                    println!("  In Clojure mode, input is compiled to UAT.");
                    println!("  In Python mode, input is compiled to UAT.");
                }
                "quit" => {
                    std::process::exit(0);
                }
                "reset" => {
                    *vm = RegisterVM::new().with_std_primitives();
                    println!("RegisterVM reset");
                }
                "lang" => {
                    if let Some(Value::Keyword(lang)) = v.get(1) {
                        match lang.as_ref() {
                            "clojure" => return Some("clojure"),
                            "python" => return Some("python"),
                            _ => println!("Unsupported language: :{}", lang),
                        }
                    }
                }
                "vm" => {
                    println!("Only :register VM is currently supported in Rust.");
                }
                _ => {}
            }
        }
    }
    None
}

fn execute_uat(vm: &mut RegisterVM, uat: &Value, last_values: &mut [Value; 3]) {
    let (instrs, reg_count) = vm.compile_uat(uat);
    let artifact = assemble(&instrs);
    vm.load_artifact(artifact, reg_count);

    // Inject last values
    vm.store.insert(Value::symbol("*1"), last_values[0].clone());
    vm.store.insert(Value::symbol("*2"), last_values[1].clone());
    vm.store.insert(Value::symbol("*3"), last_values[2].clone());

    while !vm.halted && !vm.blocked {
        vm.step();
    }

    if vm.halted {
        println!("{}", vm.value);
        last_values[2] = last_values[1].clone();
        last_values[1] = last_values[0].clone();
        last_values[0] = vm.value.clone();
    } else if vm.blocked {
        println!(":yin/blocked");
    }
}

fn bracket_balance(s: &str) -> i32 {
    let mut depth = 0;
    let mut in_str = false;
    let mut chars = s.chars().peekable();
    while let Some(ch) = chars.next() {
        if in_str {
            if ch == '\\' {
                chars.next();
            } else if ch == '"' {
                in_str = false;
            }
        } else {
            match ch {
                '"' => in_str = true,
                '(' | '[' | '{' => depth += 1,
                ')' | ']' | '}' => depth -= 1,
                ';' => {
                    while let Some(&c) = chars.peek() {
                        if c == '\n' { break; }
                        chars.next();
                    }
                }
                _ => {}
            }
        }
    }
    depth
}
