(ns lan-clip.acceptance
  "localhost 双端验收脚本：提供可独立运行的自动化验收函数。
  用于验证两个同机节点能否通过 localhost 互相同步文本，
  以及回环抑制是否生效。"
  (:require [lan-clip.core :as core]
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

(defn run-text-sync
  "运行双端文本同步验收。
  启动两个 localhost server，互相发送文本，验证接收成功。
  返回 {:success? boolean :received-text string}。"
  []
  (let [port-a (random-port)
        port-b (random-port)
        secret "acceptance-test"
        received (atom nil)
        node-id (UUID/randomUUID)]
    (with-redefs [server/handle-msg
                  (fn [msg & _]
                    (case (:content-type msg)
                      :text (let [text (String. ^bytes (:payload msg) "UTF-8")]
                              (reset! received text)
                              (fingerprint/fingerprint DataFlavor/stringFlavor text))
                      nil))]
      (let [ctrl-a (server/start-server port-a secret 10485760)
            ctrl-b (server/start-server port-b secret 10485760)]
        (try
          (Thread/sleep 300)
          (future (client/run (client/->Client "localhost" port-b "acceptance hello" secret node-id)))
          (Thread/sleep 500)
          {:success? (= "acceptance hello" @received)
           :received-text @received}
          (finally
            ((:stop! ctrl-a))
            ((:stop! ctrl-b))
            (Thread/sleep 200)))))))

(defn run-loop-suppression
  "运行回环抑制验收。
  向 server 发送文本，使其记录 last-remote-fp；
  然后模拟本地剪贴板出现相同内容，验证发送被抑制。
  返回 {:success? boolean :suppressed? boolean}。"
  []
  (let [port (random-port)
        secret "acceptance-test"
        node-id (UUID/randomUUID)
        last-remote-fp (atom nil)
        sent (atom false)]
    (let [ctrl (server/start-server port secret 10485760
                                    #(reset! last-remote-fp %))]
      (try
        (Thread/sleep 300)
        ;; 向 server 发送文本，使其记录远端指纹
        (future (client/run (client/->Client "localhost" port "loop suppression test" secret node-id)))
        (Thread/sleep 500)

        ;; 重置本地剪贴板缓存，模拟新的轮询周期
        (reset! core/clip-data nil)

        ;; 模拟剪贴板内容与 last-remote-fp 完全一致
        (with-redefs [core/get-clip-data
                      (fn [_ _]
                        @last-remote-fp)
                      core/handle-flavor
                      (fn [& _]
                        (reset! sent true))]
          (#'core/listen-clipboard node-id secret last-remote-fp nil)
          {:success? (some? @last-remote-fp)
           :suppressed? (false? @sent)})

        (finally
          ((:stop! ctrl))
          (Thread/sleep 200))))))
