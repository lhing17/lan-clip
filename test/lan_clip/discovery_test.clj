(ns lan-clip.discovery-test
  (:require [clojure.test :refer :all]
            [lan-clip.discovery :as discovery])
  (:import (java.time Instant Duration)))

(deftest create-registry-empty
  (testing "创建注册表应为空"
    (let [reg (discovery/create-registry)]
      (is (= {} @reg)))))

(deftest register-peer
  (testing "注册 beacon 后应能在注册表中查到"
    (let [reg (discovery/create-registry)]
      (discovery/register-peer! reg "192.168.1.10"
                                {:node-id #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
                                 :device-name "Test-Mac"
                                 :port 9002
                                 :version 1})
      (let [peer (get @reg #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789")]
        (is (= "Test-Mac" (:device-name peer)))
        (is (= "192.168.1.10" (:host peer)))
        (is (= 9002 (:port peer)))))))

(deftest recent-peers-filters-expired
  (testing "过期 peer 不应出现在 recent-peers 中"
    (let [reg (discovery/create-registry)
          old-time (.minus (Instant/now) (Duration/ofSeconds 60))]
      (swap! reg assoc #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
             {:node-id #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
              :device-name "Old-Mac"
              :host "192.168.1.10"
              :port 9002
              :last-seen old-time})
      (is (= [] (discovery/recent-peers reg))))))

(deftest recent-peers-includes-fresh
  (testing "未过期 peer 应出现在 recent-peers 中"
    (let [reg (discovery/create-registry)]
      (discovery/register-peer! reg "192.168.1.11"
                                {:node-id #uuid "b2c3d4e5-f6a7-5890-bcde-f12345678901"
                                 :device-name "Fresh-Mac"
                                 :port 9003
                                 :version 1})
      (let [peers (discovery/recent-peers reg)]
        (is (= 1 (count peers)))
        (is (= "Fresh-Mac" (:device-name (first peers))))
        (is (= "192.168.1.11" (:host (first peers))))))))

(deftest recent-peers-excludes-self
  (testing "recent-peers 应过滤掉本节点"
    (let [reg (discovery/create-registry)
          self-id #uuid "c3d4e5f6-a7b8-6901-cdef-012345678901"]
      (discovery/register-peer! reg "192.168.1.12"
                                {:node-id self-id
                                  :device-name "Self-Mac"
                                  :port 9002
                                  :version 1})
      (is (= [] (discovery/recent-peers reg self-id))))))

(deftest recent-peers-sorted-by-device-name
  (testing "recent-peers 应按 device-name 排序"
    (let [reg (discovery/create-registry)]
      (discovery/register-peer! reg "192.168.1.20"
                                {:node-id #uuid "d4e5f6a7-b8c9-7012-def0-123456789012"
                                 :device-name "Zebra"
                                 :port 9002})
      (discovery/register-peer! reg "192.168.1.21"
                                {:node-id #uuid "e5f6a7b8-c9d0-8123-ef01-234567890123"
                                 :device-name "Apple"
                                 :port 9002})
      (let [peers (discovery/recent-peers reg)]
        (is (= ["Apple" "Zebra"] (map :device-name peers)))))))

(deftest register-peer-overwrites
  (testing "同一 node-id 重新注册应覆盖旧信息"
    (let [reg (discovery/create-registry)
          id #uuid "f6a7b8c9-d0e1-9234-f012-345678901234"]
      (discovery/register-peer! reg "192.168.1.30"
                                {:node-id id :device-name "First" :port 9002})
      (discovery/register-peer! reg "192.168.1.31"
                                {:node-id id :device-name "Second" :port 9003})
      (let [peer (get @reg id)]
        (is (= "Second" (:device-name peer)))
        (is (= "192.168.1.31" (:host peer)))
        (is (= 9003 (:port peer)))))))
