;; The O_CLOEXEC gate's child (J4b gate 4), exec'd as a fresh jolt runtime by
;; a jolt owner that holds <dir>'s lock and content log open:
;;
;;   cloexec_child.clj <dir>
;;
;; The identities (st_dev, st_ino) of <dir>/lock and <dir>/content.jing are
;; taken here, through descriptors of this process (the owner cannot: closing
;; any descriptor on the lock inode would drop its own record lock). The
;; content one is kept open as the positive control: the walk must find it.
;; The child then walks its own descriptors (/proc/self/fd on Linux, /dev/fd
;; on macOS), comparing each by fstat dev+inode, never by number:
;;   "CONTROL <fd> found|missing"   the walker sees a descriptor it should
;;   "MATCHES <n> <fds>"            inherited descriptors on either file
;; Then it tries the lock itself: "LOCK refused" or "LOCK acquired" (an
;; inherited handle must not make the child an owner).
(ns cloexec-child)


(load-file "native/jolt-file/probes/j4b/common.clj")
(require '[dao.posix.file :as pf])


(let [dir (first *command-line-args*)
      identity-of (fn [path]
                    (let [h (pf/open path)]
                      (try (pf/fidentity (:fd h)) (finally (pf/close! h)))))
      lock-id (identity-of (str dir "/lock"))
      control (pf/open (str dir "/content.jing"))
      content-id (pf/fidentity (:fd control))
      fd-dir (if (.exists (java.io.File. "/proc/self/fd")) "/proc/self/fd" "/dev/fd")
      fds (sort (keep parse-long (seq (.list (java.io.File. ^String fd-dir)))))
      ids (into {} (keep (fn [fd]
                           (when-let [id (try (pf/fidentity fd) (catch Throwable _ nil))]
                             [fd id])))
                fds)
      inherited (sort (keep (fn [[fd id]]
                              (when (and (not= fd (:fd control))
                                         (#{lock-id content-id} id))
                                fd))
                            ids))]
  (println "IDS lock" (pr-str lock-id) "content" (pr-str content-id))
  (println "FDS" fd-dir (pr-str fds))
  (println "CONTROL" (:fd control)
           (if (= content-id (get ids (:fd control))) "found" "missing"))
  (pf/close! control)
  (println "MATCHES" (count inherited) (pr-str (vec inherited)))
  (let [[verdict x] (common/open-or-refusal dir)]
    (if (= :refused verdict)
      (println "LOCK refused" x)
      (do (println "LOCK acquired")
          (dao.space.store/close! x)))))


(System/exit 0)
