(ns lan-clip.clipboard
  "系统剪贴板封装与可替换抽象。

  设计目标：
  - 把散落在 core.clj / socket/server.clj 中的剪贴板读写集中到一处。
  - 通过 IClipboard 协议让测试可以使用 FakeClipboard，避免依赖真实系统剪贴板。"
  (:require [lan-clip.util :as util])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer Clipboard DataFlavor StringSelection)))

(defprotocol IClipboard
  "剪贴板可替换抽象。"
  (available-flavors [this])
  (read-clipboard [this])
  (write-clipboard [this flavor content]))

(defn- system-clipboard ^Clipboard []
  (.getSystemClipboard (Toolkit/getDefaultToolkit)))

(defrecord SystemClipboard []
  IClipboard
  (available-flavors [_]
    (let [clip (system-clipboard)]
      (filterv #(.isDataFlavorAvailable clip %)
               [DataFlavor/javaFileListFlavor
                DataFlavor/imageFlavor
                DataFlavor/stringFlavor])))

  (read-clipboard [_]
    (let [clip (system-clipboard)]
      (when-let [flavor (first (filter #(.isDataFlavorAvailable clip %)
                                       [DataFlavor/javaFileListFlavor
                                        DataFlavor/imageFlavor
                                        DataFlavor/stringFlavor]))]
        {:flavor flavor
         :data   (.getData clip flavor)})))

  (write-clipboard [_ flavor content]
    (let [clip (system-clipboard)]
      (condp = flavor
        DataFlavor/stringFlavor
        (.setContents clip (StringSelection. content) nil)

        DataFlavor/imageFlavor
        (.setContents clip (util/->ImageTransferable content) nil)

        DataFlavor/javaFileListFlavor
        (.setContents clip (util/->FileListTransferable content) nil)))))

(defrecord FakeClipboard [state]
  IClipboard
  (available-flavors [_]
    (when-let [f (:flavor @state)]
      [f]))

  (read-clipboard [_]
    @state)

  (write-clipboard [_ flavor content]
    (reset! state {:flavor flavor :data content})))
