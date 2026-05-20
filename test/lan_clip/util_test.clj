(ns lan-clip.util-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as jio]
            [lan-clip.util :as util])
  (:import (java.io File)
           (java.util Collections)))

(deftest zip-bytes->files-avoids-overwriting-existing-file
  (testing "目标目录已存在同名文件时，解压应自动重命名避免覆盖"
    (let [src-dir (doto (File/createTempFile "zip-src" "") (.delete))
          dest-dir (doto (File/createTempFile "zip-dst" "") (.delete))
          temp-file (doto (File. src-dir "sample.txt") (.deleteOnExit))]
      (try
        (.mkdirs src-dir)
        (.mkdirs dest-dir)
        (spit temp-file "existing content")
        (let [zip-bytes (util/files->zip-bytes (Collections/singletonList temp-file))
              files-first (util/zip-bytes->files zip-bytes dest-dir)
              files-second (util/zip-bytes->files zip-bytes dest-dir)]
          (is (= ["sample.txt"] (mapv #(.getName %) files-first)) "第一次解压应产生 sample.txt")
          (is (= ["sample (1).txt"] (mapv #(.getName %) files-second)) "第二次解压应自动重命名为 sample (1).txt"))
        (finally
          (when (.exists src-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory src-dir))
          (when (.exists dest-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory dest-dir)))))))
