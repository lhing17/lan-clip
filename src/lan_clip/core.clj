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

(defn- best-fit-flavor [^Clipboard clip _]
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
    (client/run clnt)))

(defmethod handle-flavor DataFlavor/javaFileListFlavor [clip _]
  (let [data (.getData clip DataFlavor/javaFileListFlavor)]
    (doseq [d data]
      (println (type d)))))

(defrecord ClipboardData [^DataFlavor flavor length contents])

(def clip-data (atom nil))

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

(defn clip-data-changed? [new-clip-data]
  (or (not= (:flavor @clip-data) (:flavor new-clip-data))
      (not= (:length @clip-data) (:length new-clip-data))
      (not= (:contents @clip-data) (:contents new-clip-data))))

(comment
  (defonce clip (.getSystemClipboard (Toolkit/getDefaultToolkit)))
  (defonce conf (util/read-edn "config.edn"))
  (def merge-conf (merge {:port 9002 :target-host "localhost" :target-port 9002} conf))
  (best-fit-flavor clip merge-conf)
  (get-clip-data clip merge-conf)
  @clip-data
  (clip-data-changed? (get-clip-data clip merge-conf))
  (reset! clip-data (get-clip-data clip merge-conf))
  (handle-flavor clip merge-conf)
  
  ,)



(defn lan-clip []
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        conf (util/read-edn "config.edn")
        merged-conf (merge {:port 9002 :target-host "localhost" :target-port 9002}
                           conf)]
    (println merged-conf)

    ;; 默认每隔2秒钟访问剪切版的内容，可以通过:interval进行配置
    (util/set-interval (:interval merged-conf 2000) (fn []
                              (let [new-clip-data (get-clip-data clip merged-conf)]
                                (when (clip-data-changed? new-clip-data)
                                  (reset! clip-data new-clip-data)
                                  (handle-flavor clip merged-conf)))))
    (.run (server/->Server (int (:port merged-conf))))))


(defn -main [& _]
  (lan-clip))
