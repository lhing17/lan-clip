(ns lan-clip.log
  "lan-clip 日志中心：提供结构化日志记录与最近日志查询。"
  (:require [clojure.string :as str]))

(def ^:private max-log-entries 100)

(def ^:private log-buffer
  "最近日志条目原子，按时间顺序存储，最多保留 100 条。"
  (atom []))

(defn clear-logs!
  "清空最近日志缓存（主要用于测试）。"
  []
  (reset! log-buffer []))

(defn log!
  "记录一条日志：输出到 stdout 并保留到最近日志缓存。
  level 为关键字，如 :info :warn :error 等。"
  [level msg]
  (let [entry {:time (java.util.Date.)
               :level level
               :msg msg}]
    (println (str "[" (name level) "] " msg))
    (swap! log-buffer
           #(let [v (conj % entry)]
              (if (> (count v) max-log-entries)
                (subvec v (- (count v) max-log-entries))
                v)))))

(defn recent-logs
  "返回最近日志条目，默认最多 100 条，按时间倒序（最新在前）。"
  ([] (recent-logs max-log-entries))
  ([n]
   (->> (reverse @log-buffer)
        (take n)
        (vec))))
