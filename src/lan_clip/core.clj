(ns lan-clip.core
  (:gen-class)
  (:require [clojure.java.io :as jio]
            [lan-clip.util :as util]
            [lan-clip.socket.client :as client]
            [lan-clip.socket.server :as server])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor ClipboardOwner Clipboard)))

(defn- set-owner [clip owner]
  (.setContents clip (.getContents clip nil) owner))

(defrecord Owner
  []

  ClipboardOwner
  (lostOwnership [this clipboard _]
    (Thread/sleep 1000)
    (when (.isDataFlavorAvailable clipboard DataFlavor/stringFlavor)
      (println (.getData clipboard DataFlavor/stringFlavor))
      (set-owner clipboard this))))

(defn- best-fit-flavor
  "获取最适合当前剪贴板内容的flavor，根据测试结果，选择了文件列表>图像>字符串，这样可以使MacOS和Windows上的表现一致。"
  [^Clipboard clip _]
  (first (filter #(.isDataFlavorAvailable clip %)
                 [DataFlavor/javaFileListFlavor
                  DataFlavor/imageFlavor
                  DataFlavor/stringFlavor])))

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
        total-size  (-> (map #(.length ^java.io.File %) data)
                        (#(apply + %))
                        (quot 1024))
        clnt (client/->Client (:target-host conf) (:target-port conf) data)]
    ;; TODO 如果文件总大小小于2M，默认直接传送到目标机器的临时文件夹，并设置到目标机器的剪贴板
    (if (< total-size 2048)
      (future (client/run clnt))
      (println "文件太大，文件大小为" total-size "K"))))

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
  (future (client/run (client/->Client "localhost" 9002 data)))
  
  ,)

(defn- listen-clipboard []
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        conf (util/read-edn "config.edn")
        merged-conf (merge {:port 9002 :target-host "localhost" :target-port 9002} conf)]
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
    (future (.run (server/->Server (int (:port merged-conf)))))))


(defn -main [& _]
  (lan-clip))

(comment
  (-main))
