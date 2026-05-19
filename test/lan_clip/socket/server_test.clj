(ns lan-clip.socket.server-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.server :as server])
  (:import (java.net ServerSocket Socket)))

(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest server-can-start-and-stop
  (testing "server 应能启动并在 stop 后释放端口"
    (let [port (random-port)
          ctrl (server/start-server port "test-secret" 10485760)]
      (try
        (Thread/sleep 200)
        (is (some? (:future ctrl)) "应有 future")
        (is (not (future-done? (:future ctrl))) "server 应在运行中")
        (with-open [socket (Socket. "localhost" port)]
          (is (.isConnected socket)) "端口应可连接")
        (finally
          ((:stop! ctrl))
          (Thread/sleep 200)
          (is (future-done? (:future ctrl)) "stop 后 future 应完成"))))))
