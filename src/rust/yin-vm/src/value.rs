use std::sync::Arc;
use im::{HashMap, Vector};
use ordered_float::OrderedFloat;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Value {
    Nil,
    Boolean(bool),
    Integer(i64),
    Float(OrderedFloat<f64>),
    String(Arc<str>),
    Symbol(Arc<str>),
    Keyword(Arc<str>),
    Vector(Vector<Value>),
    Map(HashMap<Value, Value>),
    Closure(Arc<Closure>),
    ReifiedContinuation(Arc<Frame>),
    NativeFn(NativeFn),
    Stream(StreamValue),
    Cursor(CursorValue),
}

#[derive(Clone)]
pub struct StreamValue(pub Arc<dyn crate::stream::DaoStream>);

impl std::fmt::Debug for StreamValue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Stream")
    }
}

impl PartialEq for StreamValue {
    fn eq(&self, other: &Self) -> bool {
        Arc::as_ptr(&self.0) as *const () == Arc::as_ptr(&other.0) as *const ()
    }
}

impl Eq for StreamValue {}

impl std::hash::Hash for StreamValue {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        (Arc::as_ptr(&self.0) as *const ()).hash(state);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CursorValue {
    pub stream: StreamValue,
    pub position: usize,
}

#[derive(Clone)]
pub struct NativeFn(pub Arc<dyn Fn(Vec<Value>) -> Value + Send + Sync>);

impl std::fmt::Debug for NativeFn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "NativeFn")
    }
}

impl PartialEq for NativeFn {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.0, &other.0)
    }
}

impl Eq for NativeFn {}

impl std::hash::Hash for NativeFn {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        Arc::as_ptr(&self.0).hash(state);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Closure {
    pub params: Vector<Value>,
    pub body_addr: usize,
    pub reg_count: usize,
    pub env: HashMap<Value, Value>,
    pub bytecode: Arc<Vec<i32>>,
    pub pool: Arc<Vec<Value>>,
    pub version: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Frame {
    pub result_reg: Option<usize>,
    pub control: usize,
    pub regs: Vector<Value>,
    pub env: HashMap<Value, Value>,
    pub bytecode: Arc<Vec<i32>>,
    pub pool: Arc<Vec<Value>>,
    pub version: Option<u64>,
    pub next: Option<Arc<Frame>>,
}

impl std::fmt::Display for Value {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Value::Nil => write!(f, "nil"),
            Value::Boolean(b) => write!(f, "{}", b),
            Value::Integer(i) => write!(f, "{}", i),
            Value::Float(fv) => write!(f, "{}", fv.0),
            Value::String(s) => write!(f, "\"{}\"", s),
            Value::Symbol(s) => write!(f, "{}", s),
            Value::Keyword(k) => write!(f, ":{}", k),
            Value::Vector(v) => {
                write!(f, "[")?;
                for (i, val) in v.iter().enumerate() {
                    if i > 0 { write!(f, " ")?; }
                    write!(f, "{}", val)?;
                }
                write!(f, "]")
            }
            Value::Map(m) => {
                write!(f, "{{")?;
                for (i, (k, v)) in m.iter().enumerate() {
                    if i > 0 { write!(f, ", ")?; }
                    write!(f, "{} {}", k, v)?;
                }
                write!(f, "}}")
            }
            Value::Closure(_) => write!(f, "#<closure>"),
            Value::ReifiedContinuation(_) => write!(f, "#<continuation>"),
            Value::NativeFn(_) => write!(f, "#<native-fn>"),
            Value::Stream(_) => write!(f, "#<stream>"),
            Value::Cursor(c) => write!(f, "#<cursor {}>", c.position),
        }
    }
}

impl Value {
    pub fn is_truthy(&self) -> bool {
        match self {
            Value::Nil => false,
            Value::Boolean(b) => *b,
            _ => true,
        }
    }

    pub fn keyword(s: &str) -> Self {
        Value::Keyword(Arc::from(s))
    }

    pub fn symbol(s: &str) -> Self {
        Value::Symbol(Arc::from(s))
    }
}
