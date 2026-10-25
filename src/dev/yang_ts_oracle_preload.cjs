// Preload for the T1 oracle (docs/design/yang.typescript.md section 9): an
// uncaught throw becomes a line on stdout, in order with console.log, so the
// harness compares one stream.
process.on('uncaughtException', (e) => {
  console.log('Uncaught ' + (e instanceof Error ? e.name + ': ' + e.message : String(e)));
  process.exit(0);
});
