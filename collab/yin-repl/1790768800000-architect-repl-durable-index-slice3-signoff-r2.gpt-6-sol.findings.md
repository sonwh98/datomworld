No actionable findings.

The new fixture covers three original transaction times, a retraction, and a greatest ID that appears only in `m`. The test compares restored transaction groups and history and current query results before and after publication, and checks that new IDs exceed 40. The reported mutations each failed the relevant assertions. VM selection now reports durable index retention while preserving the memory-mode message.

I found no regression in the slice 2 lock, HEAD, validation, or close paths. I treated the reported JVM, Node, and Dart results as evidence and did not rerun them.

**Verdict: SIGN-OFF GRANTED**
