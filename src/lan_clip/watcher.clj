(ns lan-clip.watcher
  "可停止的剪贴板轮询 watcher，替代 util.clj 中不可停止的 set-interval。"
  (:require [lan-clip.log :as log])
  (:import (java.util.concurrent Future)))

(defn start-watcher
  "启动一个周期性 watcher，每隔 interval 毫秒调用一次 callback。
  返回控制对象，包含两个键：
    :future — 后台 future，可通过 future-done? 检查状态
    :stop!  — 无参函数，调用后将停止 watcher"
  [interval callback]
  (let [running (volatile! true)]
    {:future (future
               (while @running
                 (try
                   (Thread/sleep interval)
                   (when @running
                     (callback))
                   (catch InterruptedException _
                     (vreset! running false))
                   (catch Exception e
                     (log/log! :error (str e))))))
     :stop! #(vreset! running false)}))

(defn stop-watcher
  "停止 watcher。非阻塞；watcher 将在下一次循环检查或 sleep 被中断后退出。"
  [watcher]
  (when-let [stop-fn (:stop! watcher)]
    (stop-fn))
  (when-let [f ^Future (:future watcher)]
    (.cancel f true)))
