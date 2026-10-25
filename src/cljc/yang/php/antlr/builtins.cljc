(ns yang.php.antlr.builtins
  "The pinned PHP builtin catalogue (docs/design/yang.php.md section 3.8): the
   lower-cased names of the PHP 8.3 core functions a program is likely to
   call. A call to a name in this set that is neither declared by the unit nor
   supplied by the prelude gets the `:yang.php/unknown-builtin` warning
   diagnostic at lowering, so an unsupported library function fails honestly
   before the program runs. At run time such a call is still
   `Error: Call to undefined function`; nothing is stubbed to return
   success. The list is checked in, not derived: regenerate it from
   `get_defined_functions()['internal']` of the pinned PHP when the target
   version moves.")


(def catalogue
  #{;; strings
    "addslashes" "bin2hex" "chr" "chunk_split" "count_chars" "crc32" "explode"
    "html_entity_decode" "htmlentities" "htmlspecialchars" "implode" "join"
    "lcfirst" "levenshtein" "ltrim" "md5" "nl2br" "number_format" "ord"
    "printf" "rtrim" "sha1" "similar_text" "sprintf" "str_contains"
    "str_ends_with" "str_pad" "str_repeat" "str_replace" "str_split"
    "str_starts_with" "str_word_count" "strcasecmp" "strcmp" "strip_tags"
    "stripos" "stripslashes" "stristr" "strnatcmp" "strncasecmp" "strncmp"
    "strpos" "strrchr" "strrev" "strripos" "strrpos" "strstr" "strtolower"
    "strtoupper" "strtr" "substr" "substr_count" "substr_replace" "trim"
    "ucfirst" "ucwords" "vsprintf" "wordwrap"
    ;; multibyte
    "mb_strlen" "mb_substr" "mb_strtolower" "mb_strtoupper" "mb_strpos"
    "mb_str_split" "mb_internal_encoding" "mb_strwidth"
    ;; arrays
    "array_chunk" "array_column" "array_combine" "array_count_values"
    "array_diff" "array_diff_key" "array_fill" "array_fill_keys" "array_filter"
    "array_flip" "array_intersect" "array_intersect_key" "array_is_list"
    "array_key_exists" "array_key_first" "array_key_last" "array_keys"
    "array_map" "array_merge" "array_merge_recursive" "array_multisort"
    "array_pad" "array_pop" "array_product" "array_push" "array_rand"
    "array_reduce" "array_replace" "array_reverse" "array_search" "array_shift"
    "array_slice" "array_splice" "array_sum" "array_unique" "array_unshift"
    "array_values" "array_walk" "arsort" "asort" "compact" "count" "current"
    "end" "extract" "in_array" "key" "krsort" "ksort" "list" "natsort" "next"
    "prev" "range" "reset" "rsort" "shuffle" "sizeof" "sort" "uasort" "uksort"
    "usort"
    ;; math
    "abs" "acos" "asin" "atan" "atan2" "base_convert" "bindec" "ceil" "cos"
    "decbin" "dechex" "decoct" "deg2rad" "exp" "floor" "hexdec" "hypot"
    "is_finite" "is_infinite" "log" "log10" "log2" "max" "min" "mt_getrandmax"
    "mt_rand" "mt_srand" "octdec" "pi" "pow" "rad2deg" "rand" "random_int"
    "round" "sin" "sqrt" "srand" "tan"
    ;; variables and types
    "boolval" "empty" "floatval" "gettype" "get_debug_type" "intval" "is_array"
    "is_bool" "is_callable" "is_countable" "is_float" "is_int" "is_integer"
    "is_iterable" "is_null" "is_numeric" "is_object" "is_scalar" "is_string"
    "isset" "print_r" "serialize" "settype" "strval" "unserialize" "unset"
    "var_export" "json_encode" "json_decode"
    ;; functions and classes
    "call_user_func" "call_user_func_array" "class_exists" "func_get_arg"
    "func_get_args" "func_num_args" "function_exists" "get_class"
    "get_object_vars" "method_exists" "property_exists" "spl_autoload_register"
    "spl_object_id" "iterator_to_array"
    ;; output, errors, runtime
    "assert" "debug_zval_refcount" "error_log" "flush" "ini_get" "ini_set"
    "ob_end_clean" "ob_end_flush" "ob_get_clean" "ob_get_contents" "ob_start"
    "set_exception_handler" "trigger_error" "user_error" "usleep" "sleep"
    "memory_get_usage" "microtime" "time" "date" "mktime" "strtotime"
    "date_default_timezone_set" "hrtime" "uniqid" "define" "defined"
    "constant" "php_sapi_name" "phpversion" "getenv" "gc_collect_cycles"
    "file_get_contents" "file_put_contents" "fopen" "fclose" "fwrite" "fgets"
    "file_exists" "preg_match" "preg_match_all" "preg_replace" "preg_split"
    "preg_quote" "preg_replace_callback"})
