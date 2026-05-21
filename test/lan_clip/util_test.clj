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

(deftest zip-preserves-directory-structure
  (testing "zip 打包应保留文件相对于最近公共父目录的目录结构"
    (let [base-dir (doto (File/createTempFile "zip-base" "") (.delete))
          dest-dir (doto (File/createTempFile "zip-dst" "") (.delete))
          sub-dir (File. base-dir "subdir")
          _ (.mkdirs sub-dir)
          file1 (doto (File. base-dir "root.txt") (.deleteOnExit))
          file2 (doto (File. sub-dir "nested.txt") (.deleteOnExit))]
      (try
        (spit file1 "root content")
        (spit file2 "nested content")
        (let [zip-bytes (util/files->zip-bytes [file1 file2])
              files (util/zip-bytes->files zip-bytes dest-dir)]
          (is (= 2 (count files)))
          (is (.exists (File. dest-dir "root.txt")) "根目录文件应被解压到根")
          (is (.exists (File. (File. dest-dir "subdir") "nested.txt")) "子目录文件应保持目录结构"))
        (finally
          (when (.exists base-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory base-dir))
          (when (.exists dest-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory dest-dir)))))))

(deftest zip-bytes->files-rejects-path-traversal
  (testing "zip entry 包含 .. 时应抛出异常，防止路径遍历"
    (let [dest-dir (doto (File/createTempFile "zip-dst" "") (.delete))]
      (try
        (.mkdirs dest-dir)
        ;; 构造一个恶意 zip：entry-name 为 "../../outside.txt"
        (let [baos (java.io.ByteArrayOutputStream.)
              zos (java.util.zip.ZipOutputStream. baos)]
          (.putNextEntry zos (java.util.zip.ZipEntry. "../../outside.txt"))
          (.write zos (.getBytes "malicious" "UTF-8"))
          (.closeEntry zos)
          (.close zos)
          (is (thrown? Exception
                       (util/zip-bytes->files (.toByteArray baos) dest-dir))
              "应拒绝包含 .. 的 zip entry"))
        (finally
          (when (.exists dest-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory dest-dir)))))))
