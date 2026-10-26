/* probes/run.clj: a stand-in libjolt_file that answers another layout, so
   dao.posix.file must refuse it before any file work. -DLAYOUT=1 builds a
   J4a-era library, which the layout-2 facade must refuse too. */
#ifndef LAYOUT
#define LAYOUT 99
#endif
__attribute__((visibility("default"))) int jolt_file_layout_version(void) {
  return LAYOUT;
}
