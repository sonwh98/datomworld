(ns yin.vm.protocols
  "Protocols for VM execution.

   Defines a common interface for all VM implementations:
   - ASTWalkerVM: CESK machine that walks AST maps
   - RegisterVM: register-based bytecode VM
   - StackVM: stack-based numeric bytecode VM
   - SemanticVM: datom graph traversal VM

   All VMs implement:
   - IVMStep: single-step execution
   - IVMRun: run to completion or blocked state")


(defprotocol IVMStep
  "Single-step VM execution protocol."
  (step [vm]
    "Execute one step of the VM. Returns updated VM.")
  (halted? [vm]
    "Returns true if VM has halted (completed or error).")
  (blocked? [vm]
    "Returns true if VM is blocked waiting for external input.")
  (value [vm]
    "Returns the current result value, or nil if not yet computed."))


(defprotocol IVMRun
  "Run VM to completion protocol."
  (run [vm]
    "Run VM until halted or blocked. Returns final VM state."))


(defprotocol IVMLoad
  "Load program into VM protocol."
  (load-program [vm program]
    "Load a program into the VM. Program format is VM-specific:
     - ASTWalkerVM: AST map
     - RegisterVM: bytecode vector or {:bc [...] :pool [...]}
     - StackVM: {:bc [...] :pool [...]}
     - SemanticVM: {:node root-id :datoms [...]}
     Returns new VM with program loaded."))
