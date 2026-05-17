use crate::value::Value;
use im::HashMap;

pub fn resolve_var(
    env: &HashMap<Value, Value>,
    store: &HashMap<Value, Value>,
    primitives: &HashMap<Value, Value>,
    name: &Value,
) -> Option<Value> {
    if let Some(v) = env.get(name) {
        return Some(v.clone());
    }
    if let Some(v) = store.get(name) {
        return Some(v.clone());
    }
    if let Some(v) = primitives.get(name) {
        return Some(v.clone());
    }
    None
}
