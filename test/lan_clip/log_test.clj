(ns lan-clip.log-test
  (:require [clojure.test :refer :all]
            [lan-clip.log :as log])
  (:import (java.io File)))

(deftest log-persists-to-file
  (testing "log! 应将日志写入文件"
    (let [temp-file (doto (File/createTempFile "lan-clip" ".log") (.deleteOnExit))]
      (log/set-log-file! (.getAbsolutePath temp-file))
      (log/clear-logs!)
      (try
        (log/log! :info "file-test-entry")
        (let [content (slurp temp-file)]
          (is (re-find #"file-test-entry" content) "日志文件应包含写入内容")
          (is (re-find #"\[info\]" content) "日志文件应包含级别前缀"))
        (finally
          (log/set-log-file! nil))))))

(deftest log-creates-parent-dir
  (testing "log! 应在父目录不存在时自动创建"
    (let [parent (doto (File/createTempFile "parent" "") (.delete))
          log-file (File. parent "sub/lan-clip.log")]
      (.deleteOnExit parent)
      (log/set-log-file! (.getAbsolutePath log-file))
      (log/clear-logs!)
      (try
        (log/log! :warn "dir-test-entry")
        (is (.exists log-file) "日志文件应被创建")
        (is (re-find #"dir-test-entry" (slurp log-file)))
        (finally
          (log/set-log-file! nil)
          (when (.exists parent)
            (org.apache.commons.io.FileUtils/deleteDirectory parent)))))))
