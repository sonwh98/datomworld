#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Opcode {
    Literal = 1,
    LoadVar = 2,
    Move = 3,
    Lambda = 4,
    Call = 5,
    Return = 6,
    Branch = 7,
    Jump = 8,
    Gensym = 9,
    StoreGet = 10,
    StorePut = 11,
    StreamMake = 12,
    StreamPut = 13,
    StreamCursor = 14,
    StreamNext = 15,
    StreamClose = 16,
    Park = 17,
    Resume = 18,
    CurrentCont = 19,
    Tailcall = 20,
    DaoStreamApplyCall = 21,
}

impl Opcode {
    pub fn from_u8(n: u8) -> Option<Self> {
        match n {
            1 => Some(Opcode::Literal),
            2 => Some(Opcode::LoadVar),
            3 => Some(Opcode::Move),
            4 => Some(Opcode::Lambda),
            5 => Some(Opcode::Call),
            6 => Some(Opcode::Return),
            7 => Some(Opcode::Branch),
            8 => Some(Opcode::Jump),
            9 => Some(Opcode::Gensym),
            10 => Some(Opcode::StoreGet),
            11 => Some(Opcode::StorePut),
            12 => Some(Opcode::StreamMake),
            13 => Some(Opcode::StreamPut),
            14 => Some(Opcode::StreamCursor),
            15 => Some(Opcode::StreamNext),
            16 => Some(Opcode::StreamClose),
            17 => Some(Opcode::Park),
            18 => Some(Opcode::Resume),
            19 => Some(Opcode::CurrentCont),
            20 => Some(Opcode::Tailcall),
            21 => Some(Opcode::DaoStreamApplyCall),
            _ => None,
        }
    }
}
