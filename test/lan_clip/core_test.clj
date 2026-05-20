(ns lan-clip.core-test
  (:require [clojure.test :refer :all]
            [lan-clip.core :refer :all])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor StringSelection)
           (java.util UUID)))

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
            (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp)
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
          (#'lan-clip.core/listen-clipboard node-id secret-key last-remote-fp)
          (is (true? @sent) "应发送")
          (is (= (:contents (get-clip-data clip nil)) (:contents @clip-data))))))))
