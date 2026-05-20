(ns lan-clip.api
  "lan-clip sidecar HTTP 管理 API。
  提供可被 Tauri 前端调用的 REST 端点，用于查询状态、管理配置、控制同步生命周期。"
  (:require [clojure.edn :as edn]
            [org.httpkit.server :as hk]
            [lan-clip.app :as app]
            [lan-clip.config :as config]
            [lan-clip.core :as core]))

(def ^:private config-path-atom
  "配置文件的默认路径，可通过 set-config-path! 修改（主要用于测试）。"
  (atom "config.edn"))

(defn set-config-path!
  "设置配置文件路径。"
  [path]
  (reset! config-path-atom path))

(defn- app-state []
  (app/status))

(defn- safe-config
  "返回不含敏感字段的配置 map。"
  [cfg]
  (dissoc cfg :secret-key))

(defn- handler [req]
  (case [(:request-method req) (:uri req)]
    [:get "/status"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (app-state))}

    [:get "/config"]
    (let [cfg (or (app/current-config)
                  (config/load-config nil))]
      {:status 200
       :headers {"Content-Type" "application/edn"}
       :body (pr-str (safe-config cfg))})

    [:put "/config"]
    (let [body-str (slurp (:body req))
          updates (edn/read-string body-str)
          current (or (config/load-config @config-path-atom)
                      (config/load-config nil))
          merged (merge current updates)]
      (config/save-config! @config-path-atom merged)
      {:status 200
       :headers {"Content-Type" "application/edn"}
       :body (pr-str {:success? true})})

    [:post "/sync/start"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (app/start! @config-path-atom (core/make-clipboard-handler)))}

    [:post "/sync/stop"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (app/stop!))}

    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found"}))

(defn start-api-server
  "启动 HTTP API server，返回 server 对象（一个可调用的停止函数）。"
  [port]
  (hk/run-server handler {:port port}))

(defn stop-api-server
  "停止 HTTP API server。"
  [server]
  (server :timeout 100))
