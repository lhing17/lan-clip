(ns lan-clip.pairing-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as jio]
            [lan-clip.app :as app]
            [lan-clip.config :as config]
            [lan-clip.discovery :as discovery])
  (:import (java.util UUID)))

(deftest handle-incoming-msg-routes-beacon
  (testing "不含 :msg-type 的消息应被当作 beacon 处理"
    (let [reg (discovery/create-registry)
          called (atom nil)]
      (discovery/handle-incoming-msg reg "192.168.1.10"
                                     {:node-id #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
                                      :device-name "Test" :port 9002}
                                     nil nil)
      (is (= "Test" (:device-name (get @reg #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789")))))))

(deftest handle-incoming-msg-routes-pair-request
  (testing ":pair-request 消息应触发 on-pair-request 回调"
    (let [reg (discovery/create-registry)
          received (atom nil)]
      (discovery/handle-incoming-msg reg "192.168.1.20"
                                     {:msg-type :pair-request
                                      :node-id #uuid "b2c3d4e5-f6a7-5890-bcde-f12345678901"
                                      :device-name "Peer-A"
                                      :port 9002
                                      :secret-key "shared-secret"}
                                     (fn [host msg] (reset! received {:host host :msg msg}))
                                     nil)
      (is (= "192.168.1.20" (:host @received)))
      (is (= "shared-secret" (get-in @received [:msg :secret-key]))))))

(deftest handle-incoming-msg-routes-pair-response
  (testing ":pair-response 消息应触发 on-pair-response 回调"
    (let [reg (discovery/create-registry)
          received (atom nil)]
      (discovery/handle-incoming-msg reg "192.168.1.30"
                                     {:msg-type :pair-response
                                      :node-id #uuid "c3d4e5f6-a7b8-6901-cdef-012345678901"
                                      :device-name "Peer-B"
                                      :port 9002
                                      :status :accepted}
                                     nil
                                     (fn [host msg] (reset! received {:host host :msg msg})))
      (is (= :accepted (get-in @received [:msg :status]))))))

(deftest pair-request-updates-config
  (testing "收到 pair-request 后应更新 target-host/target-port/secret-key"
    (let [config-file (str (System/getProperty "java.io.tmpdir") "/test-pair-config-" (UUID/randomUUID) ".edn")]
      (config/save-config! config-file {:port 9002 :target-host "localhost" :target-port 9002
                                        :secret-key "old-key" :device-name "Self"})
      (app/handle-pair-request "192.168.1.50"
                               {:node-id #uuid "d4e5f6a7-b8c9-7012-def0-123456789012"
                                :device-name "Peer-C"
                                :port 9003
                                :secret-key "new-shared-key"}
                               config-file)
      (let [loaded (config/load-config config-file)]
        (is (= "192.168.1.50" (:target-host loaded)))
        (is (= 9003 (:target-port loaded)))
        (is (= "new-shared-key" (:secret-key loaded))))
      (jio/delete-file config-file true))))

(deftest pair-response-accepted-updates-config
  (testing "收到接受的 pair-response 后应更新 target-host/target-port"
    (let [config-file (str (System/getProperty "java.io.tmpdir") "/test-pair-resp-" (UUID/randomUUID) ".edn")]
      (config/save-config! config-file {:port 9002 :target-host "localhost" :target-port 9002
                                        :secret-key "my-key" :device-name "Self"})
      (app/handle-pair-response "192.168.1.60"
                                {:node-id #uuid "e5f6a7b8-c9d0-8123-ef01-234567890123"
                                 :device-name "Peer-D"
                                 :port 9004
                                 :status :accepted}
                                config-file)
      (let [loaded (config/load-config config-file)]
        (is (= "192.168.1.60" (:target-host loaded)))
        (is (= 9004 (:target-port loaded)))
        (is (= "my-key" (:secret-key loaded))))
      (jio/delete-file config-file true))))

(deftest pair-response-rejected-does-not-update
  (testing "收到拒绝的 pair-response 后不应更新配置"
    (let [config-file (str (System/getProperty "java.io.tmpdir") "/test-pair-rej-" (UUID/randomUUID) ".edn")]
      (config/save-config! config-file {:port 9002 :target-host "localhost" :target-port 9002
                                        :secret-key "my-key" :device-name "Self"})
      (app/handle-pair-response "192.168.1.70"
                                {:node-id #uuid "f6a7b8c9-d0e1-9234-f012-345678901234"
                                 :device-name "Peer-E"
                                 :port 9005
                                 :status :rejected}
                                config-file)
      (let [loaded (config/load-config config-file)]
        (is (= "localhost" (:target-host loaded)))
        (is (= 9002 (:target-port loaded))))
      (jio/delete-file config-file true))))
