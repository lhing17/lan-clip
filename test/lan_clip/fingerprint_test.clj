(ns lan-clip.fingerprint-test
  (:require [clojure.test :refer :all]
            [lan-clip.fingerprint :as fp]
            [lan-clip.util :as util])
  (:import (java.awt.image BufferedImage)
           (java.awt Color)
           (java.io File)
           (java.awt.datatransfer DataFlavor)))

(defn- temp-file
  "在系统临时目录创建文件，写入 content 后返回 File。"
  ^File [^String name ^String content]
  (let [f (File/createTempFile name ".tmp")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- test-image
  "创建一个 2x2 的 BufferedImage，每个像素颜色不同，用于测试。"
  ^BufferedImage []
  (let [bi (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics bi)]
    (try
      (.setColor g (Color. 255 0 0))
      (.drawRect g 0 0 1 1)
      (.setColor g (Color. 0 255 0))
      (.drawRect g 1 0 1 1)
      (.setColor g (Color. 0 0 255))
      (.drawRect g 0 1 1 1)
      (.setColor g (Color. 255 255 255))
      (.drawRect g 1 1 1 1)
      bi
      (finally
        (.dispose g)))))

(deftest text-fingerprint
  (testing "文本内容生成正确 fingerprint"
    (let [text "hello lan-clip"
          f (fp/fingerprint DataFlavor/stringFlavor text)]
      (is (instance? lan_clip.fingerprint.ClipboardData f))
      (is (= DataFlavor/stringFlavor (:flavor f)))
      (is (= (count text) (:length f)))
      (is (= (util/md5 text) (:contents f))))))

(deftest image-fingerprint
  (testing "图片内容生成正确 fingerprint"
    (let [img (test-image)
          f (fp/fingerprint DataFlavor/imageFlavor img)]
      (is (instance? lan_clip.fingerprint.ClipboardData f))
      (is (= DataFlavor/imageFlavor (:flavor f)))
      (is (= (count (util/image->bytes img)) (:length f)))
      (is (= (util/md5 img) (:contents f))))))

(deftest file-list-fingerprint
  (testing "文件列表生成正确 fingerprint"
    (let [fs [(temp-file "aaa" "alpha") (temp-file "bbb" "beta")]
          f (fp/fingerprint DataFlavor/javaFileListFlavor fs)]
      (is (instance? lan_clip.fingerprint.ClipboardData f))
      (is (= DataFlavor/javaFileListFlavor (:flavor f)))
      (is (= (count fs) (:length f)))
      (is (= (util/md5 fs) (:contents f))))))

(deftest changed-detects-type-change
  (testing "不同 flavor 的 fingerprint 应判定为 changed"
    (let [text-fp (fp/fingerprint DataFlavor/stringFlavor "same")
          ;; 用同样 md5 的字符串列表无法得到，但 flavor 不同即应 changed
          img-fp (fp/fingerprint DataFlavor/imageFlavor (test-image))]
      (is (fp/changed? text-fp img-fp))
      (is (fp/changed? img-fp text-fp)))))

(deftest changed-detects-content-change
  (testing "同 flavor 但不同内容应判定为 changed"
    (let [a (fp/fingerprint DataFlavor/stringFlavor "alpha")
          b (fp/fingerprint DataFlavor/stringFlavor "beta")]
      (is (fp/changed? a b))
      (is (fp/changed? b a)))))

(deftest changed-returns-false-for-same
  (testing "完全相同的 fingerprint 不应判定为 changed"
    (let [a (fp/fingerprint DataFlavor/stringFlavor "same")
          b (fp/fingerprint DataFlavor/stringFlavor "same")]
      (is (false? (fp/changed? a b))))))
