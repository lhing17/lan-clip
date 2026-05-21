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

(def ^:private allowed-config-keys
  "允许通过 PUT /config 修改的配置键集合。"
  (set (concat (keys config/default-config) [:node-id])))

(def ^:private max-config-body-size 65536)

(defn- validate-config-updates [updates]
  "校验配置更新：拒绝未知 key 和类型错误的值。校验通过返回 updates，否则抛 ex-info。"
  (let [unknown-keys (remove allowed-config-keys (keys updates))]
    (when (seq unknown-keys)
      (throw (ex-info "Unknown config keys"
                      {:cause :unknown-keys :keys (vec unknown-keys)}))))
  (doseq [[k v] updates]
    (case k
      (:port :target-port)
      (when-not (config/valid-port? v)
        (throw (ex-info "Invalid port" {:cause :invalid-port :key k :value v})))
      (:file-size :interval :max-frame-size)
      (when-not (and (integer? v) (pos? v))
        (throw (ex-info "Invalid positive integer"
                        {:cause :invalid-value :key k :value v})))
      (:secret-key :target-host :device-name :received-files-dir :log-file)
      (when-not (string? v)
        (throw (ex-info "Invalid string value"
                        {:cause :invalid-value :key k :value v})))
      :node-id
      (when-not (instance? java.util.UUID v)
        (throw (ex-info "Invalid UUID" {:cause :invalid-value :key :node-id :value v})))
      nil))
  updates)

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
    (let [body-str (slurp (:body req))]
      (if (> (count body-str) max-config-body-size)
        {:status 413
         :headers {"Content-Type" "application/edn"}
         :body (pr-str {:error :body-too-large :max max-config-body-size})}
        (try
          (let [updates (edn/read-string body-str)
                _ (validate-config-updates updates)
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
          (catch Exception e
            (let [data (ex-data e)]
              (if data
                {:status 400
                 :headers {"Content-Type" "application/edn"}
                 :body (pr-str {:error (:cause data) :details data})}
                (throw e)))))))

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
