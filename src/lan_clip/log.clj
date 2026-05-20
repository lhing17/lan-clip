(ns lan-clip.log
  "lan-clip 日志中心：提供结构化日志记录与最近日志查询。"
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]))

(def ^:private max-log-entries 100)

(def ^:private log-buffer
  "最近日志条目原子，按时间顺序存储，最多保留 100 条。"
  (atom []))

(def ^:private log-file-atom
  "当前日志文件路径，nil 表示不落盘。"
  (atom nil))

(defn set-log-file!
  "设置日志文件路径（nil 表示不落盘）。"
  [path]
  (reset! log-file-atom path))

(defn- append-to-file
  "将一行文本追加到文件，父目录不存在时自动创建。"
  [path line]
  (let [f (jio/file path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f (str line "\n") :append true)))

(defn clear-logs!
  "清空最近日志缓存（主要用于测试）。"
  []
  (reset! log-buffer []))

(defn log!
  "记录一条日志：输出到 stdout、保留到内存缓存，并在设置了日志文件时落盘。
  level 为关键字，如 :info :warn :error 等。"
  [level msg]
  (let [entry {:time (java.util.Date.)
               :level level
               :msg msg}
        line (str "[" (name level) "] " msg)]
    (println line)
    (swap! log-buffer
           #(let [v (conj % entry)]
              (if (> (count v) max-log-entries)
                (subvec v (- (count v) max-log-entries))
                v)))
    (when-let [path @log-file-atom]
      (append-to-file path line))))

(defn recent-logs
  "返回最近日志条目，默认最多 100 条，按时间倒序（最新在前）。"
  ([] (recent-logs max-log-entries))
  ([n]
   (->> (reverse @log-buffer)
        (take n)
        (vec))))
