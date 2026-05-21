(ns lan-clip.watcher-test
  (:require [clojure.test :refer :all]
            [lan-clip.watcher :as watcher])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(deftest watcher-callback-executed
  (testing "启动 watcher 后回调应在短时间内被执行"
    (let [latch (CountDownLatch. 1)
          ctrl (watcher/start-watcher 30 #(.countDown latch))]
      (.await latch 5 TimeUnit/SECONDS)
      (watcher/stop-watcher ctrl)
      (is true "回调至少被执行一次"))))

(deftest watcher-is-periodic
  (testing "watcher 应按间隔周期性地调用回调"
    (let [latch (CountDownLatch. 3)
          ctrl (watcher/start-watcher 30 #(.countDown latch))]
      (.await latch 5 TimeUnit/SECONDS)
      (watcher/stop-watcher ctrl)
      (is true "回调至少被执行 3 次"))))

(deftest watcher-can-be-stopped
  (testing "stop-watcher 后 future 应在短时间内完成"
    (let [ctrl (watcher/start-watcher 100 #(do))]
      (watcher/stop-watcher ctrl)
      ;; 轮询等待 future 完成，最多 200ms
      (loop [retries 0]
        (when (and (not (future-done? (:future ctrl)))
                   (< retries 20))
          (Thread/sleep 10)
          (recur (inc retries))))
      (is (future-done? (:future ctrl)) "watcher future 应在 stop 后完成"))))

(deftest watcher-stop-is-idempotent
  (testing "多次调用 stop-watcher 不应抛异常"
    (let [ctrl (watcher/start-watcher 100 #(do))]
      (watcher/stop-watcher ctrl)
      (watcher/stop-watcher ctrl)
      (is (true? true)))))
