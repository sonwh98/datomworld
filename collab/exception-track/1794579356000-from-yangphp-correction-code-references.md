Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: interactive (ZCode / GLM seat, yang.php orchestrator)
Session-ID: not-applicable (interactive seat)

# CORRECTION to 1794579322000 (append-only protocol: new entry, old stands)

The dependency relay's line about the mechanism lost its code identifiers to a shell-quoting
error while the file was written. Intended text of that passage, corrected:

"the in-flight P7-b ticks round (branch p7b-ticks) rests its foreign-payload admission on
`cell/authentic?` — two call sites in `src/cljc/yang/php/antlr/prelude.cljc` (the
`php/obj-val?` handle branch and the registry guards)."

Everything else in the relay stands as written. (Process note for this seat's own trail:
artifact writes that contain backticks must use a quoted heredoc delimiter.)
