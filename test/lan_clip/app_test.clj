(ns lan-clip.app-test
  (:require [clojure.test :refer :all]
            [lan-clip.app :as app])
  (:import (java.io File)
           (java.net Socket)))

(defn- temp-config-path
  "创建临时配置文件，写入给定 EDN 内容后返回绝对路径。"
  [content]
  (let [f (File/createTempFile "app-test" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (java.net.ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest app-can-start-and-stop
  (testing "应用应能启动和停止，状态正确变化，回调被调用"
    (let [counter (atom 0)
          handler (fn [_] (swap! counter inc))
          port (random-port)
          path (temp-config-path (str "{:port " port " :interval 50}"))]
      (try
        (let [started (app/start! path handler)]
          (is (:running? started))
          (is (= port (:port (:config started)))))
        (Thread/sleep 150)
        (is (>= @counter 1) (str "handler 应至少被调用一次，实际 " @counter " 次"))
        (let [stopped (app/stop!)]
          (is (not (:running? stopped))))
        (finally
          (app/stop!))))))

(deftest app-uses-default-config
  (testing "不传配置文件时应使用默认配置"
    (let [handler (fn [_] nil)]
      (try
        (let [started (app/start! nil handler)]
          (is (:running? started))
          (is (some? (:config started)))
          (is (= "localhost" (:target-host (:config started)))))
        (finally
          (app/stop!))))))

(deftest app-status-reflects-state
  (testing "status 应反映当前运行状态与配置摘要"
    (let [port (random-port)
          path (temp-config-path (str "{:port " port " :interval 100}"))]
      (try
        (app/start! path (fn [_] nil))
        (let [s (app/status)]
          (is (:running? s))
          (is (contains? (:config s) :port))
          (is (contains? (:config s) :target-host))
          (is (contains? (:config s) :interval)))
        (app/stop!)
        (let [s (app/status)]
          (is (not (:running? s))))
        (finally
          (app/stop!))))))

(deftest app-start-includes-server
  (testing "start! 应启动 Netty server，stop! 应停止 server"
    (let [port (random-port)
          path (temp-config-path (str "{:port " port " :interval 100}"))]
      (try
        (app/start! path (fn [_] nil))
        (Thread/sleep 300)
        (with-open [socket (Socket. "localhost" port)]
          (is (.isConnected socket)) "Netty server 端口应可连接")
        (app/stop!)
        (Thread/sleep 300)
        (finally
          (app/stop!))))))
