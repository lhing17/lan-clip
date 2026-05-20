(ns lan-clip.acceptance-test
  (:require [clojure.test :refer :all]
            [lan-clip.acceptance :as acceptance]
            [lan-clip.socket.server :as server]
            [lan-clip.socket.client :as client]
            [lan-clip.fingerprint :as fingerprint])
  (:import (java.net ServerSocket)
           (java.util UUID)
           (java.awt.datatransfer DataFlavor)))

(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest acceptance-run-text-sync-passes
  (testing "acceptance/run-text-sync 应验证双端文本同步成功"
    (let [result (acceptance/run-text-sync)]
      (is (:success? result))
      (is (= "acceptance hello" (:received-text result))))))

(deftest acceptance-run-loop-suppression-passes
  (testing "acceptance/run-loop-suppression 应验证回环抑制成功"
    (let [result (acceptance/run-loop-suppression)]
      (is (:success? result))
      (is (true? (:suppressed? result))))))

(deftest dual-node-text-sync
  (testing "两个 localhost 节点应能互相同步文本"
    (let [port-a (random-port)
          port-b (random-port)
          secret "acceptance-test"
          clip-a (atom nil)
          clip-b (atom nil)
          node-id-a (UUID/randomUUID)
          node-id-b (UUID/randomUUID)]
      ;; 拦截 handle-msg，将写入导向各自的 fake clipboard
      (with-redefs [server/handle-msg
                    (fn [msg & _]
                      (case (:content-type msg)
                        :text (let [text (String. ^bytes (:payload msg) "UTF-8")]
                                (reset! clip-b text)
                                (fingerprint/fingerprint DataFlavor/stringFlavor text))
                        nil))]
        (let [ctrl-a (server/start-server port-a secret 10485760)
              ctrl-b (server/start-server port-b secret 10485760)]
          (try
            (Thread/sleep 300)

            ;; A 向 B 发送文本
            (future (client/run (client/->Client "localhost" port-b "hello from A" secret node-id-a)))
            (Thread/sleep 500)

            (is (= "hello from A" @clip-b) "B 应收到 A 发送的文本")

            ;; 反向：B 向 A 发送文本
            ;; 重新绑定 clip-a 为接收端
            (with-redefs [server/handle-msg
                          (fn [msg & _]
                            (case (:content-type msg)
                              :text (let [text (String. ^bytes (:payload msg) "UTF-8")]
                                      (reset! clip-a text)
                                      (fingerprint/fingerprint DataFlavor/stringFlavor text))
                              nil))]
              (future (client/run (client/->Client "localhost" port-a "hello from B" secret node-id-b)))
              (Thread/sleep 500)

              (is (= "hello from B" @clip-a) "A 应收到 B 发送的文本"))

            (finally
              ((:stop! ctrl-a))
              ((:stop! ctrl-b))
              (Thread/sleep 200))))))))
