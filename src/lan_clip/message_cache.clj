(ns lan-clip.message-cache
  "最近处理消息缓存，按数量淘汰（LRU），用于去重和回环控制。"
  (:refer-clojure :exclude [contains?]))

(defn create-cache
  "创建消息缓存，max-size 为最大保留条目数。"
  [max-size]
  (atom {:entries {} :order [] :max-size max-size}))

(defn put!
  "将 message-id 加入缓存。若已存在则更新访问顺序；若缓存已满，淘汰最久未访问的条目。"
  [cache message-id]
  (swap! cache
         (fn [{:keys [entries order max-size]}]
           (if (clojure.core/contains? entries message-id)
             ;; 已存在：移到队尾（最新访问）
             {:entries entries
              :order (conj (vec (remove #(= % message-id) order)) message-id)
              :max-size max-size}
             (let [new-entries (assoc entries message-id true)
                   new-order (conj order message-id)]
               (if (> (count new-entries) max-size)
                 (let [oldest (first new-order)]
                   {:entries (dissoc new-entries oldest)
                    :order (vec (rest new-order))
                    :max-size max-size})
                 {:entries new-entries
                  :order new-order
                  :max-size max-size}))))))

(defn contains?
  "检查 message-id 是否在缓存中。纯查询，不修改缓存。"
  [cache message-id]
  (clojure.core/contains? (:entries @cache) message-id))
