import sys

path = "/Users/sto/workspace/datomworld-r3-orch/src/cljc/yang/clojure.cljc"
with open(path) as f:
    src = f.read()

def rep(old, new):
    global src
    n = src.count(old)
    if n != 1:
        print("FAIL count=%d for old:\n%s" % (n, old[:200]))
        sys.exit(1)
    src = src.replace(old, new)
    print("OK replaced")

old1 = ''';; =============================================================================
;; The generated exception wrapper (R3, section 8.10.7; (A) split)
;; =============================================================================
;;
;; An ORDINARY `:yang.call/thrown` payload crosses a call site RAW: the
;; error-outcome slot carries the payload itself, so the caught value IS
;; the payload and every guest observation (equality in any position, map
;; keys, count, assoc, truthiness, predicates under any spelling, passage
;; to another unit) is correct by construction, with no compiler routing.
;; No wrapper, no retention: the wire condition is reconstructible from
;; the payload as a fresh `:yang.call/thrown` at the public boundary.
;;
;; The wrapper survives ONLY as clojure's documented local form for
;; QUALIFIED protocol conditions (`:yang.call/not-callable`,
;; `:invalid-invocation`, `:unsupported-binding`): a task-owned cell whose
;; contents are [visible condition], minted through the issuer-only
;; retained mint (`cell/dsg__new-retained`, a name guest source cannot
;; spell). Retention is bound to heap identity, not contents: the engine
;; marks the wrapper's heap entry `:retained`, `cell/set!` refuses marked
;; entries (contents frozen for the wrapper's lifetime), and the pure
;; `cell/retained?` query answers from the heap, so a guest copy of the
;; contents in a fresh cell decodes as an ordinary payload, never as a
;; retained condition. The association is the cell's identity, so saving
;; and returning the wrapper works, and so does a rethrow from another
;; activation. Decoding reads cell contents and never applies a guest
;; value.

(def ^:private visible-of-form'''

new1 = ''';; =============================================================================
;; The generated exception wrapper (R3, section 8.10.7; (A) split, NO-VM)
;; =============================================================================
;;
;; An ORDINARY `:yang.call/thrown` payload crosses a call site RAW: the
;; error-outcome slot carries the payload itself, so the caught value IS
;; the payload and every guest observation (equality in any position, map
;; keys, count, assoc, truthiness, predicates under any spelling, passage
;; to another unit) is correct by construction, with no compiler routing.
;; No wrapper, no retention: the wire condition is reconstructible from
;; the payload as a fresh `:yang.call/thrown` at the public boundary.
;;
;; The wrapper survives ONLY as clojure's documented local form for
;; QUALIFIED protocol conditions (`:yang.call/not-callable`,
;; `:invalid-invocation`, `:unsupported-binding`): a task-owned cell whose
;; contents are [visible condition witness]. The witness authenticates the
;; COMPLETE contents: it is a closure only this compiler can mint (its body
;; names the compiler-held token), and the escape boundary rebuilds it from
;; the visible value and condition and compares, exactly as the descriptor
;; admission witness does. Copying a witness onto different contents fails;
;; reading it yields no transferable token. The association is the cell's
;; identity, so saving and returning the wrapper works, and so does a
;; rethrow from another activation. Decoding reads cell contents and never
;; applies a guest value.
;;
;; The carrier primitive is the guest-admissible `cell/new`, not an
;; issuer-only mint: the witness — not heap identity — is what
;; authenticates. Known limitation of this choice (owner's NO, ruling
;; consequence 2): a guest that catches the wrapper and mutates its cell
;; via `cell/set!` before rethrowing forwards the SUBSTITUTED condition;
;; the witness binds contents, not the cell's lifetime. See
;; `test-yang-clojure-witness-set-bang-accepted` in clojure_test.clj.

(def ^:private condition-witness-form
  '(fn [dsg__visible dsg__condition]
     (fn [dsg__query]
       (if (dsg__equal dsg__query dsg__call_token)
         (conj (conj [] dsg__visible) dsg__condition)
         nil))))


(def ^:private visible-of-form'''
rep(old1, new1)

old2 = '''(def ^:private condition-wrapper-form
  '(fn [dsg__visible dsg__condition]
     (cell/dsg__new-retained
       (conj (conj [] dsg__visible) dsg__condition))))'''
new2 = '''(def ^:private condition-wrapper-form
  '(fn [dsg__visible dsg__condition]
     (let [dsg__vis (dsg__visible_of dsg__visible)]
       (cell/new
         (conj (conj (conj [] dsg__vis) dsg__condition)
               (dsg__condition_witness dsg__vis dsg__condition))))))'''
rep(old2, new2)

old3 = '''(def ^:private condition-retained-form
  '(fn [dsg__candidate]
     (if (data/map? dsg__candidate)
       (if (dsg__equal (dsg__get dsg__candidate :type) :cell-ref)
         (if (data/contains? dsg__candidate :id)
           (if (data/contains? dsg__candidate :seal)
             (if (data/string? (dsg__get dsg__candidate :seal))
               (if (dsg__equal (data/str-length (dsg__get dsg__candidate :seal)) 64)
                 (if (cell/retained? dsg__candidate)
                   (let [dsg__contents (cell/get dsg__candidate)]
                     (if (data/vector? dsg__contents)
                       (if (dsg__equal (data/count dsg__contents) 2)
                         (conj (conj [] (data/nth dsg__contents 0))
                               (data/nth dsg__contents 1))
                         nil)
                       nil))
                   nil)
                 nil)
               nil)
             nil)
           nil)
         nil)
       nil)))'''
new3 = '''(def ^:private condition-retained-form
  '(fn [dsg__candidate]
     (if (data/map? dsg__candidate)
       (if (dsg__equal (dsg__get dsg__candidate :type) :cell-ref)
         (if (data/contains? dsg__candidate :id)
           (if (data/contains? dsg__candidate :seal)
             (if (data/string? (dsg__get dsg__candidate :seal))
               (if (dsg__equal (data/str-length (dsg__get dsg__candidate :seal)) 64)
                 (if (cell/authentic? dsg__candidate)
                   (let [dsg__contents (cell/get dsg__candidate)]
                     (if (data/vector? dsg__contents)
                       (if (dsg__equal (data/count dsg__contents) 3)
                         (let [dsg__visible (data/nth dsg__contents 0)
                               dsg__condition (data/nth dsg__contents 1)
                               dsg__proof (data/nth dsg__contents 2)]
                           (if (data/callable? dsg__proof)
                             (if (dsg__equal dsg__proof
                                             (dsg__condition_witness
                                               dsg__visible dsg__condition))
                               (conj (conj [] dsg__visible) dsg__condition)
                               nil)
                             nil))
                         nil)
                       nil))
                   nil)
                 nil)
               nil)
             nil)
           nil)
         nil)
       nil)))'''
rep(old3, new3)

old4 = '''           ;; The wrapper helpers. Dependency order matters: the cond->> chain
           ;; nests each later binding outside the earlier one, and a binding's
           ;; value expression sees only the bindings outside it. So the
           ;; wrapper (which names only the retained mint) comes first, the
           ;; accessors and the visible form (which name the decoder) precede
           ;; the decoder, and the decoder precedes the token binding.
           (outcomes? env) (u/let1 'dsg__condition_wrapper
                                   (u/sexp->uast condition-wrapper-form))
           (outcomes? env) (u/let1 'dsg__visible_of
                                   (u/sexp->uast visible-of-form))
           (outcomes? env) (u/let1 'dsg__ex_message
                                   (u/sexp->uast ex-message-form))
           (outcomes? env) (u/let1 'dsg__ex_data
                                   (u/sexp->uast ex-data-form))
           (outcomes? env) (u/let1 'dsg__condition_retained
                                   (u/sexp->uast condition-retained-form))
           (outcomes? env) (u/then'''
new4 = '''           ;; The wrapper helpers. Dependency order matters: the cond->> chain
           ;; nests each later binding outside the earlier one, and a binding's
           ;; value expression sees only the bindings outside it. So the
           ;; wrapper (which names the witness) precedes the witness, the
           ;; accessors (which name the decoder) precede the decoder, and the
           ;; witness (which names the token) precedes the token binding.
           (outcomes? env) (u/let1 'dsg__condition_wrapper
                                   (u/sexp->uast condition-wrapper-form))
           (outcomes? env) (u/let1 'dsg__visible_of
                                   (u/sexp->uast visible-of-form))
           (outcomes? env) (u/let1 'dsg__ex_message
                                   (u/sexp->uast ex-message-form))
           (outcomes? env) (u/let1 'dsg__ex_data
                                   (u/sexp->uast ex-data-form))
           (outcomes? env) (u/let1 'dsg__condition_retained
                                   (u/sexp->uast condition-retained-form))
           (outcomes? env) (u/let1 'dsg__condition_witness
                                   (u/sexp->uast condition-witness-form))
           (outcomes? env) (u/then'''
rep(old4, new4)

with open(path, "w") as f:
    f.write(src)
print("DONE")
