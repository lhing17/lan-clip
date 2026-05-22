(ns lan-clip.sidecar-test
  (:require [clojure.test :refer :all]
            [lan-clip.sidecar :as sidecar]
            [lan-clip.api :as api]
            [org.httpkit.client :as http])
  (:import (java.net ServerSocket)))
(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))
(deftest sidecar-start-prints-ready
  (testing "sidecar 启动后应输出 READY 到 stdout"
    (let [port (random-port)
          output (with-out-str
                   (let [server (sidecar/start! port)]
                     (try
                       (finally
                         (api/stop-api-server server)))))]
      (is (re-find #"READY" output) "应包含 READY 标记"))))
(deftest sidecar-start-starts-api-server
  (testing "sidecar 启动后 API server 应可访问"
    (let [port (random-port)
          server (sidecar/start! port)]
      (try
        (let [{:keys [status]} @(http/get (str "http://localhost:" port "/status"))]
          (is (= 200 status) "API server 应返回 200"))
        (finally
          (api/stop-api-server server))))))
