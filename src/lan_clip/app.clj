(ns lan-clip.app
  "lan-clip 应用生命周期管理：提供统一的 start!、stop!、status 入口。"
  (:require [lan-clip.config :as config]
            [lan-clip.discovery :as discovery]
            [lan-clip.history :as history]
            [lan-clip.socket.server :as server]
            [lan-clip.watcher :as watcher]))

(def ^:private app-state
  (atom nil))

(def ^:private last-remote-fp
  (atom nil))

(def ^:private history-store
  (history/create-store 100))

(def ^:private discovery-registry
  (discovery/create-registry))

(defn last-remote-fingerprint
  "返回最近一次远端写入剪贴板的内容指纹（ClipboardData），若尚未收到则为 nil。"
  []
  @last-remote-fp)

(defn current-config
  "返回当前运行中的完整配置；若应用未运行则返回 nil。"
  []
  (when-let [st @app-state]
    (:config st)))

(defn current-history-store
  "返回当前历史记录存储 atom；若应用未运行也返回存储（全局单例）。"
  []
  history-store)

(defn current-discovery-registry
  "返回当前设备发现注册表 atom；若应用未运行也返回注册表（全局单例）。"
  []
  discovery-registry)

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
  - clipboard-handler: 每次轮询触发的回调，签名为 (fn [config last-remote-fp]) -> nil
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
                                       #(clipboard-handler validated last-remote-fp))
         s-ctrl (server/start-server (:port validated)
                                     (:secret-key validated)
                                     (:max-frame-size validated)
                                     #(reset! last-remote-fp %)
                                     (:received-files-dir validated)
                                     history-store)]
     (let [d-ctrl (discovery/start-discovery (:node-id validated)
                                             (:device-name validated)
                                             (:port validated)
                                             discovery-registry)]
       (reset! app-state {:running? true
                          :config   validated
                          :watcher  w-ctrl
                          :server   s-ctrl
                          :history  history-store
                          :discovery d-ctrl})
       (status)))))

(defn stop!
  "停止 lan-clip 应用。非阻塞；watcher 将在下一次循环检查后退出。
  返回当前状态 map。"
  []
  (when-let [st @app-state]
    (when-let [w (:watcher st)]
      (watcher/stop-watcher w))
    (when-let [s (:server st)]
      ((:stop! s)))
    (when-let [d (:discovery st)]
      ((:stop! d))))
  (history/clear! history-store)
  (reset! app-state nil)
  (reset! last-remote-fp nil)
  (status))
