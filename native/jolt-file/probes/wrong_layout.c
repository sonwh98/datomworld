/* probes/run.clj: a stand-in libjolt_file that answers another layout, so
   dao.posix.file must refuse it before any file work. */
__attribute__((visibility("default"))) int jolt_file_layout_version(void) {
  return 99;
}
