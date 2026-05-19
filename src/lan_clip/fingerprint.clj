(ns lan-clip.fingerprint
  "剪贴板内容指纹模块：将原始内容（文本 / 图片 / 文件列表）抽象为
  可比较、可传输的 `ClipboardData` 记录，用于变化检测与同步决策。"
  (:require [lan-clip.util :as util])
  (:import (java.awt.datatransfer DataFlavor)))

;; 剪贴板内容的摘要记录。
;; - flavor   : java.awt.datatransfer.DataFlavor，标识内容类型
;; - length   : 内容长度（文本字符数 / 图片 PNG 字节数 / 文件列表个数）
;; - contents : 内容的 MD5 十六进制摘要，用于快速比对
(defrecord ClipboardData [flavor length contents])

(defn fingerprint
  "根据 DataFlavor 和原始内容生成 ClipboardData 摘要。
  支持 :stringFlavor / :imageFlavor / :javaFileListFlavor 三种类型。"
  [flavor content]
  (condp = flavor
    DataFlavor/stringFlavor
    (->ClipboardData flavor (count content) (util/md5 content))

    DataFlavor/imageFlavor
    (->ClipboardData flavor
                     (count (util/image->bytes (util/buffered-image content)))
                     (util/md5 content))

    DataFlavor/javaFileListFlavor
    (->ClipboardData flavor (count content) (util/md5 content))))

(defn changed?
  "比较两个 ClipboardData，判断内容是否发生变化。
  只要 flavor、length 或 contents（md5）任一不同，即视为已变。"
  [old new]
  (or (not= (:flavor old) (:flavor new))
      (not= (:length old) (:length new))
      (not= (:contents old) (:contents new))))
