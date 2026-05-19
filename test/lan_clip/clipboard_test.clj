(ns lan-clip.clipboard-test
  (:require [clojure.test :refer :all]
            [lan-clip.clipboard :as cb])
  (:import (java.awt.image BufferedImage)
           (java.awt Color)
           (java.io File)
           (java.awt.datatransfer DataFlavor)))

(defn- test-image
  "创建一个 1x1 的 BufferedImage 用于测试。"
  ^BufferedImage []
  (let [bi (BufferedImage. 1 1 BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics bi)]
    (try
      (.setColor g (Color. 255 0 0))
      (.drawRect g 0 0 1 1)
      bi
      (finally
        (.dispose g)))))

(defn- temp-file
  ^File [^String name ^String content]
  (let [f (File/createTempFile name ".tmp")]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest fake-clipboard-starts-empty
  (testing "新创建的 FakeClipboard 应返回 nil 读与空可用类型"
    (let [fake (cb/->FakeClipboard (atom nil))]
      (is (empty? (cb/available-flavors fake)))
      (is (nil? (cb/read-clipboard fake))))))

(deftest fake-clipboard-read-write-text
  (testing "FakeClipboard 应能写入并读回文本"
    (let [fake (cb/->FakeClipboard (atom nil))
          text "hello clipboard"]
      (cb/write-clipboard fake DataFlavor/stringFlavor text)
      (let [result (cb/read-clipboard fake)]
        (is (= DataFlavor/stringFlavor (:flavor result)))
        (is (= text (:data result)))))))

(deftest fake-clipboard-read-write-image
  (testing "FakeClipboard 应能写入并读回图片"
    (let [fake (cb/->FakeClipboard (atom nil))
          img (test-image)]
      (cb/write-clipboard fake DataFlavor/imageFlavor img)
      (let [result (cb/read-clipboard fake)]
        (is (= DataFlavor/imageFlavor (:flavor result)))
        (is (identical? img (:data result)))))))

(deftest fake-clipboard-read-write-file-list
  (testing "FakeClipboard 应能写入并读回文件列表"
    (let [fake (cb/->FakeClipboard (atom nil))
          fs [(temp-file "aaa" "alpha") (temp-file "bbb" "beta")]]
      (cb/write-clipboard fake DataFlavor/javaFileListFlavor fs)
      (let [result (cb/read-clipboard fake)]
        (is (= DataFlavor/javaFileListFlavor (:flavor result)))
        (is (identical? fs (:data result)))))))

(deftest fake-clipboard-available-flavors-reflects-state
  (testing "available-flavors 应只反映当前写入的 flavor"
    (let [fake (cb/->FakeClipboard (atom nil))]
      (is (empty? (cb/available-flavors fake)))
      (cb/write-clipboard fake DataFlavor/stringFlavor "x")
      (is (= [DataFlavor/stringFlavor] (cb/available-flavors fake))))))

(deftest system-clipboard-implements-protocol
  (testing "SystemClipboard 应实现 IClipboard 协议"
    (let [sys (cb/->SystemClipboard)]
      (is (satisfies? cb/IClipboard sys))
      (is (seqable? (cb/available-flavors sys))))))
