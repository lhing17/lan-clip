(ns lan-clip.history
  "传输历史记录管理。
  提供环形缓冲区存储最近 N 条传输记录，不保存真实内容，只保存摘要信息
  （时间戳、方向、类型、大小、对端标识）。"
  (:import (java.time Instant)))

(defn create-store
  "创建历史记录存储，max-entries 为最大保留条数（默认 100）。"
  ([] (create-store 100))
  ([max-entries]
   (atom {:entries []
          :max-entries max-entries})))

(defn record!
  "向存储中添加一条历史记录。当超过最大条数时丢弃最旧的记录。
  entry 为 map，至少包含 :timestamp :direction :type :size :peer。"
  [store entry]
  (swap! store
         (fn [{:keys [entries max-entries]}]
           {:entries (vec (take-last max-entries (conj entries entry)))
            :max-entries max-entries})))

(defn recent
  "返回最近 limit 条历史记录（默认 20），按时间倒序。"
  ([store] (recent store 20))
  ([store limit]
   (->> (:entries @store)
        (reverse)
        (take limit)
        (vec))))

(defn clear!
  "清空历史记录。"
  [store]
  (swap! store assoc :entries []))
