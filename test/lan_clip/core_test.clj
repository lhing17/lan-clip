(ns lan-clip.core-test
  (:require [clojure.test :refer :all]
            [lan-clip.core :refer :all]
            [lan-clip.socket.client :as client])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor StringSelection Clipboard)
           (java.io File)
           (java.util UUID Collections)))

(deftest core-namespace-loads
  (testing "lan-clip.core 命名空间应可成功加载"
    (is (some? (find-ns 'lan-clip.core)))))

(deftest listen-clipboard-suppresses-when-matches-last-remote
  (testing "当剪贴板内容与 last-remote-fp 匹配时，应抑制发送并更新 clip-data"
    (let [node-id (UUID/randomUUID)
          secret-key "test-secret"
          last-remote-fp (atom nil)
          sent (atom false)]
      (reset! clip-data nil)
      (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
            text "loop suppression test"]
        (.setContents clip (StringSelection. text) nil)
        (let [fp (get-clip-data clip nil)]
          (reset! last-remote-fp fp)
          (with-redefs [handle-flavor (fn [& _] (reset! sent true))]
            (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp nil)
            (is (false? @sent) "不应发送")
            (is (= (:contents fp) (:contents @clip-data)) "clip-data 应更新为远端指纹")))))))

(deftest listen-clipboard-sends-when-not-matching-last-remote
  (testing "当剪贴板内容与 last-remote-fp 不匹配时，应正常发送"
    (let [node-id (UUID/randomUUID)
          secret-key "test-secret"
          last-remote-fp (atom nil)
          sent (atom false)]
      (reset! clip-data nil)
      (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
            text "send this content"]
        (.setContents clip (StringSelection. text) nil)
        (reset! last-remote-fp (->ClipboardData DataFlavor/stringFlavor 999 "completely-different-md5-hash-here"))
        (with-redefs [handle-flavor (fn [& _] (reset! sent true))]
          (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp nil)
          (is (true? @sent) "应发送")
          (is (= (:contents (get-clip-data clip nil)) (:contents @clip-data))))))))

(deftest listen-clipboard-logs-local-change
  (testing "本地剪贴板变化时应输出 local-change"
    (let [node-id (UUID/randomUUID)
          secret-key "test-secret"
          last-remote-fp (atom nil)]
      (reset! clip-data nil)
      (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
        (.setContents clip (StringSelection. "local change log") nil)
        (with-redefs [handle-flavor (fn [& _] nil)]
          (let [output (with-out-str
                         (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp nil))]
            (is (re-find #"local-change" output) "应包含 local-change 日志")))))))

(deftest listen-clipboard-logs-loop-suppressed
  (testing "回环抑制时应输出 loop-suppressed"
    (let [node-id (UUID/randomUUID)
          secret-key "test-secret"
          last-remote-fp (atom nil)]
      (reset! clip-data nil)
      (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
            text "loop suppressed log"]
        (.setContents clip (StringSelection. text) nil)
        (let [fp (get-clip-data clip nil)]
          (reset! last-remote-fp fp)
          (with-redefs [handle-flavor (fn [& _] nil)]
            (let [output (with-out-str
                           (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp nil))]
              (is (re-find #"loop-suppressed" output) "应包含 loop-suppressed 日志"))))))))

(defn- mock-clipboard-with-files [files]
  (proxy [Clipboard] ["test"]
    (isDataFlavorAvailable [flavor]
      (= flavor DataFlavor/javaFileListFlavor))
    (getData [flavor]
      (when (= flavor DataFlavor/javaFileListFlavor)
        files))))

(deftest handle-flavor-rejects-oversized-files
  (testing "文件超过 :file-size 限制时应拒绝发送并输出 file-too-large"
    (let [temp-file (doto (File/createTempFile "oversized" ".txt")
                      (.deleteOnExit))
          _ (spit temp-file "some content")
          conf {:file-size 0
                :target-host "localhost"
                :target-port 9002}
          sent (atom false)
          mock-clip (mock-clipboard-with-files (Collections/singletonList temp-file))]
      (with-redefs [client/->Client (fn [& _] (reify client/RunnableClient (run [_] (reset! sent true))))]
        (let [output (with-out-str
                       (handle-flavor mock-clip conf (UUID/randomUUID) "secret"))]
          (is (false? @sent) "超限文件不应发送")
          (is (re-find #"file-too-large" output) "应包含 file-too-large 日志"))))))

(deftest send-client-cancels-previous-in-flight
  (testing "连续快速调用 send-client 应取消上一个仍在飞行中的 future"
    (with-redefs [client/run (fn [_] (Thread/sleep 500))]
      (let [mock-client (reify client/RunnableClient (run [_] (client/run nil)))
            f1 (#'lan-clip.core/send-client mock-client)]
        (Thread/sleep 50)
        (let [f2 (#'lan-clip.core/send-client mock-client)]
          (is (future-cancelled? f1) "旧 future 应被取消")
          (is (= f2 @@#'lan-clip.core/in-flight-send) "in-flight-send 应指向最新 future")
          (future-cancel f2))))))
