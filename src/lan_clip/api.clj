(ns lan-clip.api
  "lan-clip sidecar HTTP 管理 API。
  提供可被 Tauri 前端调用的 REST 端点，用于查询状态、管理配置、控制同步生命周期。"
  (:require [clojure.edn :as edn]
            [org.httpkit.server :as hk]
            [lan-clip.app :as app]
            [lan-clip.config :as config]
            [lan-clip.core :as core]
            [lan-clip.log :as log]))

(def ^:private config-path-atom
  "配置文件的默认路径，可通过 set-config-path! 修改（主要用于测试）。"
  (atom "config.edn"))

(defn set-config-path!
  "设置配置文件路径。"
  [path]
  (reset! config-path-atom path))

(def ^:private app-version "1.0")
(def ^:private protocol-version 1)

(defn- app-state []
  (let [st (app/status)]
    (cond-> (merge st
                   {:version app-version
                    :protocol-version protocol-version})
      (:running? st) (assoc :node-id (get-in st [:config :node-id])))))

(def ^:private sensitive-keys
  "需要过滤的敏感配置字段集合。"
  #{:secret-key :log-file :received-files-dir})

(defn- safe-config
  "返回不含敏感字段的配置 map。"
  [cfg]
  (apply dissoc cfg sensitive-keys))

(def ^:private allowed-origins
  "允许的 CORS 来源集合。"
  #{"http://localhost" "tauri://localhost"})

(defn- with-cors
  "为响应 map 添加 CORS 头，根据请求的 Origin 动态设置 Allow-Origin。"
  [req response]
  (let [origin (get-in req [:headers "origin"])
        allowed-origin (if (allowed-origins origin) origin "http://localhost")]
    (update response :headers merge
            {"Access-Control-Allow-Origin" allowed-origin
             "Access-Control-Allow-Methods" "GET, POST, PUT, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"})))

(defn- handler* [req]
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
          merged (merge current updates)
          restart-required? (some (fn [k]
                                    (and (contains? updates k)
                                         (not= (get current k) (get updates k))))
                                  config/restart-required-keys)]
      (config/save-config! @config-path-atom merged)
      {:status 200
       :headers {"Content-Type" "application/edn"}
       :body (pr-str {:success? true
                      :restart-required? (boolean restart-required?)})})

    [:post "/sync/start"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (app/start! @config-path-atom (core/make-clipboard-handler)))}

    [:post "/sync/stop"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (app/stop!))}

    [:get "/logs/recent"]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str (log/recent-logs))}

    (let [method (:request-method req)
          uri (:uri req)]
      (cond
        (and (= method :get) (= uri "/transfers"))
        {:status 200
         :headers {"Content-Type" "application/edn"}
         :body (pr-str [])}

        (and (= method :get) (re-matches #"/transfers/[^/]+" uri))
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}

        (and (= method :post) (re-matches #"/transfers/[^/]+/cancel" uri))
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}

        :else
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))

(defn- handler [req]
  (if (= :options (:request-method req))
    (with-cors req {:status 204})
    (with-cors req (handler* req))))

(defn start-api-server
  "启动 HTTP API server，返回 server 对象（一个可调用的停止函数）。"
  [port]
  (hk/run-server handler {:port port}))

(defn stop-api-server
  "停止 HTTP API server。"
  [server]
  (server :timeout 100))
