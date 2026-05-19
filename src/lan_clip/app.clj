(ns lan-clip.app
  "lan-clip 应用生命周期管理：提供统一的 start!、stop!、status 入口。"
  (:require [lan-clip.config :as config]
            [lan-clip.socket.server :as server]
            [lan-clip.watcher :as watcher]))

(def ^:private app-state
  (atom nil))

(defn status
  "返回当前应用状态。"
  []
  (if-let [st @app-state]
    {:running? (:running? st)
     :config   (select-keys (:config st)
                            [:port :target-host :target-port :interval])}
    {:running? false}))

(declare stop!)

(defn start!
  "启动 lan-clip 应用。
  - conf-path: 配置文件路径字符串；传 nil 时使用默认配置。
  - clipboard-handler: 每次轮询触发的回调，签名为 (fn [config]) -> nil
  返回当前状态 map（含 :running? 与 :config）。"
  ([clipboard-handler]
   (start! nil clipboard-handler))
  ([conf-path clipboard-handler]
   (when @app-state
     (stop!))
   (let [cfg (if (and conf-path (seq conf-path))
               (config/load-config conf-path)
               config/default-config)
         validated (config/validate-config cfg)
         w-ctrl (watcher/start-watcher (:interval validated)
                                       #(clipboard-handler validated))
         s-ctrl (server/start-server (:port validated)
                                     (:secret-key validated)
                                     (:max-frame-size validated))]
     (reset! app-state {:running? true
                        :config   validated
                        :watcher  w-ctrl
                        :server   s-ctrl})
     (status))))

(defn stop!
  "停止 lan-clip 应用。非阻塞；watcher 将在下一次循环检查后退出。
  返回当前状态 map。"
  []
  (when-let [st @app-state]
    (when-let [w (:watcher st)]
      (watcher/stop-watcher w))
    (when-let [s (:server st)]
      ((:stop! s))))
  (reset! app-state nil)
  (status))
