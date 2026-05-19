(ns lan-clip.app-test
  (:require [clojure.test :refer :all]
            [lan-clip.app :as app])
  (:import (java.io File)
           (java.net Socket)
           (java.util UUID)))

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

(deftest app-double-start-is-idempotent
  (testing "重复启动应先停止旧实例再启动新实例，不泄漏端口"
    (let [port (random-port)
          path (temp-config-path (str "{:port " port " :interval 50}"))]
      (try
        (app/start! path (fn [_] nil))
        (Thread/sleep 200)
        ;; 重复启动：旧实例应先被停止，新实例绑定同一端口
        (let [restarted (app/start! path (fn [_] nil))]
          (is (:running? restarted))
          (is (= port (:port (:config restarted)))))
        (Thread/sleep 200)
        (with-open [socket (Socket. "localhost" port)]
          (is (.isConnected socket)) "重复启动后端口仍应可连接")
        (app/stop!)
        (finally
          (app/stop!))))))

(deftest app-tracks-last-remote-fingerprint
  (testing "应用收到远端消息后应记录 last-remote-fingerprint"
    (let [port (random-port)
          path (temp-config-path (str "{:port " port " :interval 100 :secret-key \"app-test-secret\"}"))
          node-id (UUID/randomUUID)
          secret "app-test-secret"]
      (try
        (app/start! path (fn [_] nil))
        (Thread/sleep 300)
        (is (nil? (app/last-remote-fingerprint)) "启动后应为 nil")
        ;; 通过 socket 发送一条文本消息
        (let [encoder-ch (io.netty.channel.embedded.EmbeddedChannel.
                           (into-array io.netty.channel.ChannelHandler
                                       [(lan-clip.socket.protocol-codec/->protocol-encoder node-id node-id secret)]))
              _ (.writeOutbound encoder-ch (into-array Object ["app remote text"]))
              frame (.readOutbound encoder-ch)
              frame-bytes (byte-array (.readableBytes frame))]
          (.getBytes frame 0 frame-bytes)
          (with-open [socket (Socket. "localhost" port)
                      out (.getOutputStream socket)]
            (.write out frame-bytes)
            (.flush out))
          (Thread/sleep 500)
          (let [fp (app/last-remote-fingerprint)]
            (is (some? fp) "收到消息后 last-remote-fingerprint 不应为 nil")
            (is (= java.awt.datatransfer.DataFlavor/stringFlavor (:flavor fp)))
            (is (= 15 (:length fp))))
          (.finish encoder-ch))
        (app/stop!)
        (is (nil? (app/last-remote-fingerprint)) "停止后应为 nil")
        (finally
          (app/stop!))))))
