(ns lan-clip.core-test
  (:require [clojure.test :refer :all]
            [lan-clip.core :refer :all]))

(deftest core-namespace-loads
  (testing "lan-clip.core 命名空间应可成功加载"
    (is (some? (find-ns 'lan-clip.core)))))
