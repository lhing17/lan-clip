(ns lan-clip.core
  (:gen-class)
  (:require [lan-clip.app :as app]
            [lan-clip.config :as config]
            [lan-clip.socket.client :as client]
            [lan-clip.socket.server :as server]
            [lan-clip.util :as util])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor Clipboard)
           (java.io File)
           (java.util UUID)))

(defn- best-fit-flavor
  "获取最适合当前剪贴板内容的flavor，根据测试结果，选择了文件列表>图像>字符串，这样可以使MacOS和Windows上的表现一致。"
  [^Clipboard clip _]
  (first (filter #(.isDataFlavorAvailable clip %)
                 [DataFlavor/javaFileListFlavor
                  DataFlavor/imageFlavor
                  DataFlavor/stringFlavor])))

;; 处理剪贴版上不同的内容，发送到目标服务器
(defmulti handle-flavor (fn [clip conf _ _] (best-fit-flavor clip conf)))

(defmethod handle-flavor DataFlavor/stringFlavor [clip conf node-id secret-key]
  (let [data (.getData clip DataFlavor/stringFlavor)
        clnt (client/->Client (:target-host conf) (:target-port conf) data secret-key node-id)]
    (println data)
    (future (client/run clnt))))

(defmethod handle-flavor DataFlavor/imageFlavor [clip conf node-id secret-key]
  (let [data (.getData clip DataFlavor/imageFlavor)
        clnt (client/->Client (:target-host conf) (:target-port conf) data secret-key node-id)]
    (future (client/run clnt))))

(defmethod handle-flavor DataFlavor/javaFileListFlavor [clip conf node-id secret-key]
  (let [data (.getData clip DataFlavor/javaFileListFlavor)
        clnt (client/->Client (:target-host conf) (:target-port conf) data secret-key node-id)]
    (future (client/run clnt))))

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
  (def conf (config/load-config "config.edn"))
  (best-fit-flavor clip conf)
  (get-clip-data clip conf)
  @clip-data
  (clip-data-changed? (get-clip-data clip conf))
  (reset! clip-data (get-clip-data clip conf))
  (handle-flavor clip conf (UUID/randomUUID) "lan-clip")
  (handle-flavor clip {:port 9002 :target-host "localhost" :target-port 9002} (UUID/randomUUID) "lan-clip")
  (def data (.getData clip DataFlavor/javaFileListFlavor))
  (future (client/run (client/->Client "localhost" 9002 data "lan-clip" (UUID/randomUUID)))),)

(defn- listen-clipboard [node-id secret-key last-remote-fp]
  "监听剪贴版上的内容是否有变化，如果有变化，缓存剪贴版上的内容，并启动新客户端将剪贴板上的内容发送到目标服务器。
  若当前内容与 last-remote-fp 匹配，则判定为远端回环，抑制发送并输出 loop-suppressed。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        conf (config/load-config "config.edn")]
    (let [new-clip-data (get-clip-data clip conf)]
      (when (clip-data-changed? new-clip-data)
        (if (and @last-remote-fp
                 (= (:flavor new-clip-data) (:flavor @last-remote-fp))
                 (= (:length new-clip-data) (:length @last-remote-fp))
                 (= (:contents new-clip-data) (:contents @last-remote-fp)))
          (do
            (reset! clip-data new-clip-data)
            (println "loop-suppressed"))
          (do
            (reset! clip-data new-clip-data)
            (handle-flavor clip conf node-id secret-key)))))))

(defn lan-clip []
  (let [conf (config/load-config "config.edn")
        node-id (UUID/randomUUID)
        secret-key (:secret-key conf)]

    ;; 默认每隔2秒钟访问剪切版的内容，可以通过:interval进行配置
    (util/set-interval (:interval conf 2000) #(listen-clipboard node-id secret-key))

    ;; 启动netty server，用于接收另一端传来的消息
    (-> conf (:port) (int) (server/->Server secret-key (:max-frame-size conf)) (.run) (future))))


(defn -main [& _]
  (let [node-id (UUID/randomUUID)
        secret-key (:secret-key (config/load-config "config.edn"))]
    (app/start! "config.edn" (fn [_ last-remote-fp] (listen-clipboard node-id secret-key last-remote-fp)))))

(comment
  (-main),)
