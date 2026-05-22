(ns lan-clip.socket.client-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.client :as client]))

(deftest run-with-retry-succeeds-first-try
  (testing "首次成功时不应重试"
    (let [calls (atom 0)]
      (with-redefs [client/run (fn [_] (swap! calls inc))]
        (client/run-with-retry nil 3 10)
        (is (= 1 @calls) "run 应只被调用一次")))))

(deftest run-with-retry-retries-on-failure
  (testing "失败时应重试直到成功"
    (let [calls (atom 0)]
      (with-redefs [client/run (fn [_]
                                 (swap! calls inc)
                                 (when (< @calls 3)
                                   (throw (Exception. "fail"))))]
        (client/run-with-retry nil 5 10)
        (is (= 3 @calls) "run 应被调用 3 次")))))

(deftest run-with-retry-gives-up-after-max-retries
  (testing "达到最大重试次数后应抛出异常"
    (let [calls (atom 0)]
      (with-redefs [client/run (fn [_]
                                 (swap! calls inc)
                                 (throw (Exception. "always fail")))]
        (is (thrown? Exception (client/run-with-retry nil 3 10)))
        (is (= 3 @calls) "run 应被调用 3 次")))))

(deftest run-with-retry-uses-defaults
  (testing "不传参数时应使用默认值（3 次，1000ms）"
    (let [calls (atom 0)]
      (with-redefs [client/run (fn [_]
                                 (swap! calls inc)
                                 (throw (Exception. "fail")))]
        (is (thrown? Exception (client/run-with-retry nil)))
        (is (= 3 @calls) "默认应尝试 3 次")))))
