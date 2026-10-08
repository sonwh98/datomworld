refactor(jing): rebuild observer on v2 and migrate pools

P2 and P3 of the dao.jing plan.

dao.jing's observer is rebuilt on dao.stream.observe/step with
materialize! as the effect. The dao.stream dependency is completely
removed from dao.jing. The pool's two writer seams in dao.space.index
and dao.space.transactor are moved to v2. The tests are rewritten
to run over v2 streams and ringbuffers, and exhaustive case matching
is used for the pool drains to correctly treat E11 gap/defects as fatal.

P3 is completed: docs/design/dao.jing.md is updated to embed the
T->D invariants, Decisions 1-3 (entry shape, private framed file,
and remote's deferral), and the deferred Open Items regarding durable
checkpoints and the content write path as an effect stream.

Implemented by glm-5.3 under docs/design/dao.jing.implementation-plan.md;
reviewed by claude-fable-5-1 (Architect) and gpt-5.6-sol (Routine).
