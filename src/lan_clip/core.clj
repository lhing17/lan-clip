(ns lan-clip.core
  (:gen-class)
  (:require [lan-clip.util :as util]
            [lan-clip.socket.client :as client]
            [lan-clip.socket.server :as server])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor Clipboard)
           (java.io File)))

(defn- best-fit-flavor
  "获取最适合当前剪贴板内容的flavor，根据测试结果，选择了文件列表>图像>字符串，这样可以使MacOS和Windows上的表现一致。"
  [^Clipboard clip _]
  (first (filter #(.isDataFlavorAvailable clip %)
                 [DataFlavor/javaFileListFlavor
                  DataFlavor/imageFlavor
                  DataFlavor/stringFlavor])))

;; 处理剪贴版上不同的内容，发送到目标服务器
(defmulti handle-flavor best-fit-flavor)

(defmethod handle-flavor DataFlavor/stringFlavor [clip conf]
  (let [data (.getData clip DataFlavor/stringFlavor)
        clnt (client/->Client (:target-host conf) (:target-port conf) data)]
    (future (client/run clnt))))

(defmethod handle-flavor DataFlavor/imageFlavor [clip conf]
  (let [data (.getData clip DataFlavor/imageFlavor)
        clnt (client/->Client (:target-host conf) (:target-port conf) data)]
    (future (client/run clnt))))

(defmethod handle-flavor DataFlavor/javaFileListFlavor [clip conf]
  (let [data (.getData clip DataFlavor/javaFileListFlavor)
        total-size (-> (map #(.length ^File %) data)
                       (#(apply + %))
                       (quot 1024))
        clnt (client/->Client (:target-host conf) (:target-port conf) data)]
    ;; 如果文件总大小小于设定的值，默认直接传送到目标机器的临时文件夹，并设置到目标机器的剪贴板
    (if (< total-size (:file-size conf))
      (future (client/run clnt))
      (println "文件太大，文件大小为" total-size "K，限制为" (:file-size conf) "K"))))

;; 用于描述剪贴版上内容的类 信息包括内容类型、长度和内容，其中内容类型包括字符串、图像或文件列表
;; 如果类型为字符串或图像，长度为字符串或图像的大小，如果类型为文件列表，长度为文件列表中文件数量（List的长度）
;; 内容为实际数据的md5值
(defrecord ClipboardData [^DataFlavor flavor length contents])

(def clip-data
  "临时存储每次复制后剪切版上的信息，如果非空，为`ClipboardData`类型的对象"
  (atom nil))

(defn get-clip-data [clip conf]
  (let [flavor (best-fit-flavor clip conf)
        data (.getData clip flavor)]
    (condp = flavor
      DataFlavor/stringFlavor
      (->ClipboardData flavor (count data) (util/md5 data))

      DataFlavor/imageFlavor
      (->ClipboardData flavor (count (util/image->bytes (util/buffered-image data))) (util/md5 data))

      DataFlavor/javaFileListFlavor
      (->ClipboardData flavor (count data) (util/md5 data)))))

(defn clip-data-changed?
  "判断剪贴板上的内容是否发生变化，包括类型、长度和内容（md5）"
  [new-clip-data]
  (or (not= (:flavor @clip-data) (:flavor new-clip-data))
      (not= (:length @clip-data) (:length new-clip-data))
      (not= (:contents @clip-data) (:contents new-clip-data))))

(comment
  (defonce clip (.getSystemClipboard (Toolkit/getDefaultToolkit)))
  (def conf (util/read-edn "config.edn"))
  (def merge-conf (merge {:port 9002 :target-host "localhost" :target-port 9002} conf))
  (best-fit-flavor clip merge-conf)
  (get-clip-data clip merge-conf)
  @clip-data
  (clip-data-changed? (get-clip-data clip merge-conf))
  (reset! clip-data (get-clip-data clip merge-conf))
  (handle-flavor clip merge-conf)
  (handle-flavor clip {:port 9002 :target-host "localhost" :target-port 9002})
  (def data (.getData clip DataFlavor/javaFileListFlavor))
  (future (client/run (client/->Client "localhost" 9002 data))),)

(defn- listen-clipboard []
  "监听剪贴版上的内容是否有变化，如果有变化，缓存剪贴版上的内容，并启动新客户端将剪贴板上的内容发送到目标服务器"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        conf (util/read-edn "config.edn")
        merged-conf (merge {:port 9002 :target-host "localhost" :target-port 9002 :file-size 2048} conf)]
    (let [new-clip-data (get-clip-data clip merged-conf)]
      (when (clip-data-changed? new-clip-data)
        (reset! clip-data new-clip-data)
        (handle-flavor clip merged-conf)))))

(defn lan-clip []
  (let [conf (util/read-edn "config.edn")
        merged-conf (merge {:port 9002 :target-host "localhost" :target-port 9002}
                           conf)]

    ;; 默认每隔2秒钟访问剪切版的内容，可以通过:interval进行配置
    (util/set-interval (:interval merged-conf 2000) #'listen-clipboard)

    ;; 启动netty server，用于接收另一端传来的消息
    (-> merged-conf (:port) (int) (server/->Server) (.run) (future))))


(defn -main [& _]
  (lan-clip))

(comment
  (-main),)
