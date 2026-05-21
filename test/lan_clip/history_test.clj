(ns lan-clip.history-test
  (:require [clojure.test :refer :all]
            [lan-clip.history :as history]))

(deftest create-store-defaults
  (testing "默认创建存储应有空条目和默认最大条数"
    (let [store (history/create-store)]
      (is (= [] (:entries @store)))
      (is (= 100 (:max-entries @store))))))

(deftest record-and-recent
  (testing "记录后应能查询到最近条目"
    (let [store (history/create-store 10)]
      (history/record! store {:timestamp 1 :direction :send :type :text :size 10 :peer "A"})
      (history/record! store {:timestamp 2 :direction :receive :type :image :size 100 :peer "B"})
      (let [recent (history/recent store 2)]
        (is (= 2 (count recent)))
        (is (= :receive (:direction (first recent))))
        (is (= :send (:direction (second recent))))))))

(deftest recent-limit
  (testing "recent 应尊重 limit 参数"
    (let [store (history/create-store 10)]
      (doseq [i (range 5)]
        (history/record! store {:timestamp i :direction :send :type :text :size i :peer "A"}))
      (is (= 3 (count (history/recent store 3)))))))

(deftest max-entries-eviction
  (testing "超过最大条数时应丢弃最旧的记录"
    (let [store (history/create-store 3)]
      (doseq [i (range 5)]
        (history/record! store {:timestamp i :direction :send :type :text :size i :peer "A"}))
      (let [entries (:entries @store)]
        (is (= 3 (count entries)))
        (is (= [2 3 4] (map :size entries)))))))

(deftest clear-store
  (testing "清空后条目应为空"
    (let [store (history/create-store 10)]
      (history/record! store {:timestamp 1 :direction :send :type :text :size 10 :peer "A"})
      (history/clear! store)
      (is (= [] (:entries @store))))))

(deftest recent-empty-store
  (testing "空存储应返回空向量"
    (let [store (history/create-store 10)]
      (is (= [] (history/recent store 5))))))

(deftest recent-default-limit
  (testing "recent 默认 limit 为 20"
    (let [store (history/create-store 10)]
      (doseq [i (range 25)]
        (history/record! store {:timestamp i :direction :send :type :text :size i :peer "A"}))
      (is (= 10 (count (history/recent store)))))))
