(ns lan-clip.watcher-test
  (:require [clojure.test :refer :all]
            [lan-clip.watcher :as watcher]))

(deftest watcher-callback-executed
  (testing "启动 watcher 后回调应在短时间内被执行"
    (let [counter (atom 0)
          ctrl (watcher/start-watcher 30 #(swap! counter inc))]
      (Thread/sleep 150)
      (watcher/stop-watcher ctrl)
      (is (>= @counter 1) (str "expected callback at least once, got " @counter)))))

(deftest watcher-is-periodic
  (testing "watcher 应按间隔周期性地调用回调"
    (let [counter (atom 0)
          ctrl (watcher/start-watcher 30 #(swap! counter inc))]
      (Thread/sleep 250)
      (watcher/stop-watcher ctrl)
      ;; 250ms / 30ms ≈ 8 次，留足够余量
      (is (>= @counter 3) (str "expected callback at least 3 times, got " @counter)))))

(deftest watcher-can-be-stopped
  (testing "stop-watcher 后 future 应在短时间内完成"
    (let [ctrl (watcher/start-watcher 100 #(do))]
      (Thread/sleep 50)
      (watcher/stop-watcher ctrl)
      ;; 给 future 最多 200ms 来响应停止
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
