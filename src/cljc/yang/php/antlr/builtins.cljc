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


(def supported
  "The builtins the prelude supplies: lower-cased PHP name to the prelude
   definition that implements it and, for a by-reference builtin, the
   by-reference argument positions (`:by-ref`). The prelude derives its
   registration from this table and the lowering reads `:by-ref`, so a
   position is stated once. A by-reference builtin answers `[ret outs]`."
  (let [plain (fn [m] (into {} (map (fn [[k v]] [k {:prelude v}])) m))
        by-ref (fn [m] (into {} (map (fn [[k v]] [k {:prelude v, :by-ref [0]}])) m))
        ;; the replacement count of str_replace/str_ireplace is the fourth
        ;; argument, so its by-reference position is 3, not 0
        count-out {"str_replace" {:prelude 'php/bi-str-replace, :by-ref [3]},
                   "str_ireplace" {:prelude 'php/bi-str-ireplace, :by-ref [3]}}]
    (merge count-out
           (plain {"var_dump" 'php/bi-var-dump, "intdiv" 'php/bi-intdiv, "fmod" 'php/bi-fmod,
                   "strlen" 'php/bi-strlen, "intval" 'php/bi-intval, "floatval" 'php/bi-floatval,
                   "strval" 'php/bi-strval, "boolval" 'php/bi-boolval, "is_nan" 'php/bi-is-nan,
                   "set_error_handler" 'php/bi-set-error-handler,
                   "restore_error_handler" 'php/bi-restore-error-handler,
                   "error_reporting" 'php/bi-error-reporting,
                   "trigger_error" 'php/bi-trigger-error, "user_error" 'php/bi-trigger-error,
                   "ob_start" 'php/bi-ob-start, "ob_get_contents" 'php/bi-ob-get-contents,
                   "ob_get_clean" 'php/bi-ob-get-clean, "ob_end_clean" 'php/bi-ob-end-clean,
                   "ob_end_flush" 'php/bi-ob-end-flush,
                   "header" 'php/bi-header, "headers_sent" 'php/bi-headers-sent,
                   "http_response_code" 'php/bi-http-response-code,
                   "setcookie" 'php/bi-setcookie, "setrawcookie" 'php/bi-setrawcookie,
                   "php_sapi_name" 'php/php-sapi-name, "getenv" 'php/bi-getenv,
                   "count" 'php/bi-count, "sizeof" 'php/bi-count,
                   "array_keys" 'php/bi-array-keys, "array_values" 'php/bi-array-values,
                   "array_merge" 'php/bi-array-merge,
                   "current" 'php/bi-current, "pos" 'php/bi-current, "key" 'php/bi-key,
                   "implode" 'php/bi-implode, "join" 'php/bi-implode, "array_sum" 'php/bi-array-sum,
                   "array_key_exists" 'php/bi-array-key-exists,
                   "key_exists" 'php/bi-array-key-exists, "in_array" 'php/bi-in-array,
                   "is_array" 'php/bi-is-array, "array_key_first" 'php/bi-array-key-first,
                   "array_key_last" 'php/bi-array-key-last, "get_class" 'php/bi-get-class,
                   "register_shutdown_function" 'php/bi-register-shutdown-function,
                   "define" 'php/bi-define, "defined" 'php/bi-defined, "constant" 'php/bi-constant,
                   "function_exists" 'php/bi-function-exists, "class_exists" 'php/bi-class-exists,
                   "spl_autoload_register" 'php/bi-spl-autoload-register,
                   "spl_autoload_unregister" 'php/bi-spl-autoload-unregister,
                   "spl_autoload_functions" 'php/bi-spl-autoload-functions,
                   "spl_autoload_call" 'php/bi-spl-autoload-call,
                   "set_exception_handler" 'php/bi-set-exception-handler,
                   "restore_exception_handler" 'php/bi-restore-exception-handler,
                   "substr" 'php/bi-substr, "strpos" 'php/bi-strpos, "stripos" 'php/bi-stripos,
                   "strrpos" 'php/bi-strrpos,
                   "str_contains" 'php/bi-str-contains,
                   "str_starts_with" 'php/bi-str-starts-with, "str_ends_with" 'php/bi-str-ends-with,
                   "trim" 'php/bi-trim, "ltrim" 'php/bi-ltrim, "rtrim" 'php/bi-rtrim,
                   "strtolower" 'php/bi-strtolower, "strtoupper" 'php/bi-strtoupper,
                   "str_repeat" 'php/bi-str-repeat, "str_pad" 'php/bi-str-pad, "strrev" 'php/bi-strrev,
                   "strcmp" 'php/bi-strcmp, "strcasecmp" 'php/bi-strcasecmp, "strncmp" 'php/bi-strncmp,
                   "strstr" 'php/bi-strstr, "strrchr" 'php/bi-strrchr, "substr_count" 'php/bi-substr-count,
                   "nl2br" 'php/bi-nl2br, "htmlspecialchars" 'php/bi-htmlspecialchars,
                   "html_entity_decode" 'php/bi-html-entity-decode, "addslashes" 'php/bi-addslashes,
                   "stripslashes" 'php/bi-stripslashes, "ord" 'php/bi-ord, "chr" 'php/bi-chr,
                   "bin2hex" 'php/bi-bin2hex, "hex2bin" 'php/bi-hex2bin, "explode" 'php/bi-explode,
                   "str_split" 'php/bi-str-split, "array_reverse" 'php/bi-array-reverse,
                   "array_product" 'php/bi-array-product, "array_search" 'php/bi-array-search,
                   "array_is_list" 'php/bi-array-is-list, "array_fill" 'php/bi-array-fill,
                   "array_fill_keys" 'php/bi-array-fill-keys, "array_combine" 'php/bi-array-combine,
                   "array_flip" 'php/bi-array-flip, "array_pad" 'php/bi-array-pad,
                   "array_chunk" 'php/bi-array-chunk, "array_column" 'php/bi-array-column,
                   "array_slice" 'php/bi-array-slice, "array_map" 'php/bi-array-map, "array_filter" 'php/bi-array-filter,
                   "array_reduce" 'php/bi-array-reduce, "array_unique" 'php/bi-array-unique,
                   "array_diff" 'php/bi-array-diff, "array_intersect" 'php/bi-array-intersect,
                   "array_diff_key" 'php/bi-array-diff-key, "array_intersect_key" 'php/bi-array-intersect-key,
                   "range" 'php/bi-range, "array_multisort" 'php/bi-array-multisort,
                   "abs" 'php/bi-abs, "ceil" 'php/bi-ceil, "floor" 'php/bi-floor,
                   "round" 'php/bi-round, "sqrt" 'php/bi-sqrt, "pow" 'php/bi-pow,
                   "min" 'php/bi-min, "max" 'php/bi-max, "is_finite" 'php/bi-is-finite,
                   "is_infinite" 'php/bi-is-infinite, "json_encode" 'php/bi-json-encode,
                   "json_decode" 'php/bi-json-decode})
           (by-ref {"next" 'php/bi-next, "prev" 'php/bi-prev, "reset" 'php/bi-reset, "end" 'php/bi-end,
                    "array_push" 'php/bi-array-push, "array_pop" 'php/bi-array-pop,
                    "array_shift" 'php/bi-array-shift, "array_unshift" 'php/bi-array-unshift,
                    "array_splice" 'php/bi-array-splice, "sort" 'php/bi-sort, "rsort" 'php/bi-rsort, "asort" 'php/bi-asort,
                    "arsort" 'php/bi-arsort, "ksort" 'php/bi-ksort, "krsort" 'php/bi-krsort,
                    "usort" 'php/bi-usort, "uasort" 'php/bi-uasort, "uksort" 'php/bi-uksort}))))


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


(def constants
  "The builtin constants (plan P6 R7), name to value, read by the lowering
   (a fold in the global block and at fully qualified reads) and by
   `php/init!` (seeding `php.rt/constants`, so `defined` and `constant`
   agree with the folded literals). A plain value is its own literal; the
   vector forms stand for the values a literal cannot express:
   `[:int text]` an integer past the reader's range, `:nan`, `:inf`,
   `[:float text value]`."
  (merge
    {"PHP_INT_MAX" [:int "9223372036854775807"],
     "PHP_INT_MIN" [:int "-9223372036854775808"],
     "PHP_INT_SIZE" 8,
     "PHP_EOL" "\n",
     "NAN" :nan,
     "INF" :inf,
     "M_PI" [:float "3.141592653589793" 3.141592653589793],
     "EXTR_OVERWRITE" 0, "EXTR_SKIP" 1, "EXTR_PREFIX_SAME" 2, "EXTR_PREFIX_ALL" 3,
     "EXTR_PREFIX_INVALID" 4, "EXTR_PREFIX_IF_EXISTS" 5, "EXTR_IF_EXISTS" 6,
     "EXTR_PREFIX_ONLY_IF_EXISTS" 7, "EXTR_REFS" 256,
     "JSON_OBJECT_AS_ARRAY" 1, "JSON_PRETTY_PRINT" 128, "JSON_THROW_ON_ERROR" 4194304,
     "STR_PAD_RIGHT" 1, "STR_PAD_LEFT" 0, "STR_PAD_BOTH" 2}
    (zipmap ["SORT_REGULAR" "SORT_NUMERIC" "SORT_STRING" "SORT_FLAG_CASE" "SORT_NATURAL"
             "COUNT_NORMAL" "COUNT_RECURSIVE" "E_ERROR" "E_WARNING" "E_PARSE" "E_NOTICE"
             "E_CORE_ERROR" "E_CORE_WARNING" "E_COMPILE_ERROR" "E_COMPILE_WARNING"
             "E_USER_ERROR" "E_USER_WARNING" "E_USER_NOTICE" "E_STRICT"
             "E_RECOVERABLE_ERROR" "E_DEPRECATED" "E_USER_DEPRECATED" "E_ALL"]
            [0 1 2 8 6 0 1 1 2 4 8 16 32 64 128 256 512 1024 2048 4096 8192 16384 32767])))
