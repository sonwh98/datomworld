use std::sync::Arc;
use parking_lot::Mutex;
use im::HashMap;
use crate::value::Value;

pub trait DaoStream: Send + Sync {
    fn next(&self, position: usize) -> StreamReadResult;
    fn put(&self, val: Value) -> StreamWriteResult;
    fn close(&self) -> Vec<Waiter>;
    fn is_closed(&self) -> bool;
    fn register_reader_waiter(&self, position: usize, entry: Value);
}

#[derive(Debug, Clone)]
pub enum StreamReadResult {
    Ok(Value, usize),
    Blocked,
    End,
    Gap,
}

#[derive(Debug, Clone)]
pub enum StreamWriteResult {
    Ok(Vec<Waiter>),
    Full,
}

#[derive(Debug, Clone)]
pub struct Waiter {
    pub entry: Value,
    pub value: Value,
    pub position: Option<usize>,
}

pub struct RingBufferStream {
    pub capacity: Option<usize>,
    pub state: Arc<Mutex<RingBufferState>>,
}

pub struct RingBufferState {
    pub buffer: HashMap<usize, Value>,
    pub head: usize,
    pub tail: usize,
    pub closed: bool,
    pub reader_waiters: HashMap<usize, Value>,
    pub writer_waiters: Vec<Value>,
}

impl RingBufferStream {
    pub fn new(capacity: Option<usize>) -> Self {
        Self {
            capacity,
            state: Arc::new(Mutex::new(RingBufferState {
                buffer: HashMap::new(),
                head: 0,
                tail: 0,
                closed: false,
                reader_waiters: HashMap::new(),
                writer_waiters: Vec::new(),
            })),
        }
    }
}

impl DaoStream for RingBufferStream {
    fn next(&self, position: usize) -> StreamReadResult {
        let state = self.state.lock();
        if position < state.head {
            StreamReadResult::Gap
        } else if position < state.tail {
            let val = state.buffer.get(&position).cloned().unwrap_or(Value::Nil);
            StreamReadResult::Ok(val, position + 1)
        } else if state.closed {
            StreamReadResult::End
        } else {
            StreamReadResult::Blocked
        }
    }

    fn put(&self, val: Value) -> StreamWriteResult {
        let mut state = self.state.lock();
        if state.closed {
            panic!("Cannot put to closed stream");
        }

        let available = state.tail - state.head;
        if let Some(cap) = self.capacity {
            if available >= cap {
                return StreamWriteResult::Full;
            }
        }

        let tail = state.tail;
        state.buffer.insert(tail, val.clone());
        state.tail += 1;

        let mut woke = Vec::new();
        if let Some(entry) = state.reader_waiters.remove(&tail) {
            woke.push(Waiter {
                entry,
                value: val,
                position: Some(tail),
            });
        }

        StreamWriteResult::Ok(woke)
    }

    fn close(&self) -> Vec<Waiter> {
        let mut state = self.state.lock();
        state.closed = true;
        
        let mut woke = Vec::new();
        let reader_waiters = std::mem::replace(&mut state.reader_waiters, HashMap::new());
        for (_, entry) in reader_waiters {
            woke.push(Waiter {
                entry,
                value: Value::Nil,
                position: None,
            });
        }
        let writer_waiters = std::mem::replace(&mut state.writer_waiters, Vec::new());
        for entry in writer_waiters {
            woke.push(Waiter {
                entry,
                value: Value::Nil,
                position: None,
            });
        }
        woke
    }

    fn is_closed(&self) -> bool {
        self.state.lock().closed
    }

    fn register_reader_waiter(&self, position: usize, entry: Value) {
        let mut state = self.state.lock();
        state.reader_waiters.insert(position, entry);
    }
}
