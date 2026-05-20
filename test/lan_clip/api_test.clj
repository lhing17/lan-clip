(ns lan-clip.api-test
  (:require [clojure.test :refer :all]
            [lan-clip.api :as api]
            [lan-clip.app :as app]
            [lan-clip.log :as log]
            [org.httpkit.client :as http])
  (:import (java.net ServerSocket)))

(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest api-server-can-start-and-stop
  (testing "API server 应能启动并在 stop 后释放端口"
    (let [port (random-port)
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (is (some? server) "应有 server 对象")
        (let [{:keys [status body]} @(http/get (str "http://localhost:" port "/status"))
              body-str (slurp body)]
          (is (= 200 status) "/status 应返回 200"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-status-returns-running-state
  (testing "GET /status 应返回运行状态"
    (let [port (random-port)
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/get (str "http://localhost:" port "/status"))
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (contains? parsed :running?))
          (is (false? (:running? parsed))))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-config-returns-default-config-when-not-running
  (testing "GET /config 当应用未运行时应返回默认配置（不含 secret-key）"
    (app/stop!)
    (let [port (random-port)
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/get (str "http://localhost:" port "/config"))
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (map? parsed))
          (is (contains? parsed :port))
          (is (= 9002 (:port parsed)))
          (is (contains? parsed :log-file) "应包含日志文件路径")
          (is (string? (:log-file parsed)))
          (is (not (contains? parsed :secret-key)) "不应包含 secret-key"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-start-sync-starts-app
  (testing "POST /sync/start 应启动同步并返回运行状态"
    (app/stop!)
    (with-redefs [app/start! (fn [& _] {:running? true :config {:port 9002}})
                  app/stop! (fn [] {:running? false})]
      (let [port (random-port)
            server (api/start-api-server port)]
        (try
          (Thread/sleep 200)
          (let [{:keys [status body]} @(http/post (str "http://localhost:" port "/sync/start"))
                body-str (slurp body)
                parsed (clojure.edn/read-string body-str)]
            (is (= 200 status))
            (is (true? (:running? parsed))))
          (finally
            (api/stop-api-server server)
            (Thread/sleep 200)))))))

(deftest api-stop-sync-stops-app
  (testing "POST /sync/stop 应停止同步并返回停止状态"
    (with-redefs [app/start! (fn [& _] {:running? true :config {:port 9002}})
                  app/stop! (fn [] {:running? false})]
      (let [port (random-port)
            server (api/start-api-server port)]
        (try
          (Thread/sleep 200)
          @(http/post (str "http://localhost:" port "/sync/start"))
          (let [{:keys [status body]} @(http/post (str "http://localhost:" port "/sync/stop"))
                body-str (slurp body)
                parsed (clojure.edn/read-string body-str)]
            (is (= 200 status))
            (is (false? (:running? parsed))))
          (finally
            (api/stop-api-server server)
            (Thread/sleep 200)))))))

(deftest api-put-config-saves-config
  (testing "PUT /config 应保存配置并返回成功"
    (let [temp-file (doto (java.io.File/createTempFile "config" ".edn") (.deleteOnExit))
          _ (spit temp-file (pr-str {:port 9003 :target-host "localhost"}))
          port (random-port)
          _ (api/set-config-path! (.getAbsolutePath temp-file))
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/put (str "http://localhost:" port "/config")
                                               {:body (pr-str {:target-host "192.168.1.100"})
                                                :headers {"Content-Type" "application/edn"}})
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (:success? parsed))
          (let [saved (clojure.edn/read-string (slurp temp-file))]
            (is (= "192.168.1.100" (:target-host saved)))
            (is (= 9003 (:port saved)) "原有配置应保留")))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-put-config-restart-required-for-port
  (testing "PUT /config 修改需重启项时应提示需重启"
    (let [temp-file (doto (java.io.File/createTempFile "config" ".edn") (.deleteOnExit))
          _ (spit temp-file (pr-str {:port 9002 :target-host "localhost"}))
          port (random-port)
          _ (api/set-config-path! (.getAbsolutePath temp-file))
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/put (str "http://localhost:" port "/config")
                                               {:body (pr-str {:port 9003})
                                                :headers {"Content-Type" "application/edn"}})
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (:success? parsed))
          (is (:restart-required? parsed) "修改端口应提示需重启"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-put-config-no-restart-for-target-host
  (testing "PUT /config 仅修改热更新项时不应提示需重启"
    (let [temp-file (doto (java.io.File/createTempFile "config" ".edn") (.deleteOnExit))
          _ (spit temp-file (pr-str {:port 9002 :target-host "localhost"}))
          port (random-port)
          _ (api/set-config-path! (.getAbsolutePath temp-file))
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/put (str "http://localhost:" port "/config")
                                               {:body (pr-str {:target-host "192.168.1.100"})
                                                :headers {"Content-Type" "application/edn"}})
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (:success? parsed))
          (is (not (:restart-required? parsed)) "修改 target-host 不应提示需重启"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-put-config-no-restart-for-same-value
  (testing "PUT /config 发送与现有值相同的需重启项时不应提示需重启"
    (let [temp-file (doto (java.io.File/createTempFile "config" ".edn") (.deleteOnExit))
          _ (spit temp-file (pr-str {:port 9002 :target-host "localhost"}))
          port (random-port)
          _ (api/set-config-path! (.getAbsolutePath temp-file))
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/put (str "http://localhost:" port "/config")
                                               {:body (pr-str {:port 9002})
                                                :headers {"Content-Type" "application/edn"}})
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (:success? parsed))
          (is (not (:restart-required? parsed)) "发送相同端口值不应提示需重启"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-logs-recent-returns-log-entries
  (testing "GET /logs/recent 应返回最近日志条目"
    (log/clear-logs!)
    (log/log! :info "test-log-entry-1")
    (log/log! :warn "test-log-entry-2")
    (let [port (random-port)
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/get (str "http://localhost:" port "/logs/recent"))
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (vector? parsed))
          (is (= 2 (count parsed)))
          (is (= :warn (:level (first parsed))) "最新条目应在最前")
          (is (= "test-log-entry-2" (:msg (first parsed))))
          (is (= :info (:level (second parsed))))
          (is (= "test-log-entry-1" (:msg (second parsed))))
          (is (contains? (first parsed) :time)))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))

(deftest api-logs-recent-respects-limit
  (testing "GET /logs/recent 应限制返回数量"
    (log/clear-logs!)
    (dotimes [_ 110]
      (log/log! :info "bulk-entry"))
    (let [port (random-port)
          server (api/start-api-server port)]
      (try
        (Thread/sleep 200)
        (let [{:keys [status body]} @(http/get (str "http://localhost:" port "/logs/recent"))
              body-str (slurp body)
              parsed (clojure.edn/read-string body-str)]
          (is (= 200 status))
          (is (= 100 (count parsed)) "默认最多返回 100 条"))
        (finally
          (api/stop-api-server server)
          (Thread/sleep 200))))))
