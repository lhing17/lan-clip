(ns lan-clip.message-cache-test
  (:require [clojure.test :refer :all]
            [lan-clip.message-cache :as cache]))

(deftest empty-cache-contains-nothing
  (testing "空缓存不应包含任何 message-id"
    (let [c (cache/create-cache 10)]
      (is (false? (cache/contains? c :msg-1)))
      (is (false? (cache/contains? c nil))))))

(deftest cache-contains-after-put
  (testing "put! 后缓存应包含该 message-id"
    (let [c (cache/create-cache 10)]
      (cache/put! c :msg-a)
      (is (true? (cache/contains? c :msg-a)))
      (is (false? (cache/contains? c :msg-b))))))

(deftest cache-evicts-oldest-when-full
  (testing "超过 max-size 时应淘汰最久未访问的条目"
    (let [c (cache/create-cache 3)]
      (cache/put! c :msg-1)
      (cache/put! c :msg-2)
      (cache/put! c :msg-3)
      (is (true? (cache/contains? c :msg-1)))
      ;; 加入第 4 个，最旧的 :msg-1 应被淘汰
      (cache/put! c :msg-4)
      (is (false? (cache/contains? c :msg-1)))
      (is (true? (cache/contains? c :msg-2)))
      (is (true? (cache/contains? c :msg-3)))
      (is (true? (cache/contains? c :msg-4))))))

(deftest duplicate-put-does-not-increase-size
  (testing "重复 put 同一 message-id 不应增加缓存大小"
    (let [c (cache/create-cache 3)]
      (cache/put! c :msg-1)
      (cache/put! c :msg-2)
      (cache/put! c :msg-1) ;; 重复
      (cache/put! c :msg-3)
      (cache/put! c :msg-4) ;; 应淘汰 :msg-2（最旧）
      (is (true? (cache/contains? c :msg-1)))
      (is (false? (cache/contains? c :msg-2)))
      (is (true? (cache/contains? c :msg-3)))
      (is (true? (cache/contains? c :msg-4))))))

(deftest contains?-does-not-change-cache
  (testing "contains? 应为纯查询操作，不影响缓存内容"
    (let [c (cache/create-cache 3)]
      (cache/put! c :msg-1)
      (cache/put! c :msg-2)
      (cache/put! c :msg-3)
      (cache/contains? c :msg-1) ;; 访问最旧的
      (cache/put! c :msg-4)
      ;; 由于 contains? 不更新访问时间，:msg-1 仍是最旧的
      (is (false? (cache/contains? c :msg-1)))
      (is (true? (cache/contains? c :msg-2))))))
