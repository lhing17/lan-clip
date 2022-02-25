(ns lan-clip.core
  (:require [clojure.java.io :as jio]
            [lan-clip.util :as util])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor ClipboardOwner Clipboard Transferable)
           (javax.imageio ImageIO)))

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

(defn- print-string-on-clipboard [clip]
  (println (.getData clip DataFlavor/stringFlavor)))

(defn- best-fit-flavor [^Clipboard clip]
  (first (filter #(.isDataFlavorAvailable clip %)
                 [DataFlavor/javaFileListFlavor
                  DataFlavor/imageFlavor
                  DataFlavor/stringFlavor])))

(defmulti handle-flavor best-fit-flavor)

(defmethod handle-flavor DataFlavor/stringFlavor [clip]
  (print-string-on-clipboard clip))

(defmethod handle-flavor DataFlavor/imageFlavor [clip]
  (let [data (.getData clip DataFlavor/imageFlavor)]
    (println data)
    (ImageIO/write (util/buffered-image data)
                   "png"
                   (jio/file (str "D:/" (System/currentTimeMillis) ".png")))))

(defmethod handle-flavor DataFlavor/javaFileListFlavor [clip]
  (let [data (.getData clip DataFlavor/javaFileListFlavor)]
    (doseq [d data]
      (println (type d)))))

(defrecord ClipboardData [^DataFlavor flavor length contents])

(def clip-data (atom nil))

(defn get-clip-data [clip]
  (let [flavor (best-fit-flavor clip)
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

(defrecord ImageTransferable [img]
  Transferable
  (getTransferDataFlavors [_]
    (into-array [DataFlavor/imageFlavor]))
  (isDataFlavorSupported [_ flavor]
    (= flavor DataFlavor/imageFlavor))
  (getTransferData [_ _]
    img)
  )

(defn -main [& _]
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (util/set-interval 2000 (fn []
                              (let [new-clip-data (get-clip-data clip)]
                                (when (clip-data-changed? new-clip-data)
                                  (reset! clip-data new-clip-data)
                                  (handle-flavor clip))))))

  (Thread/sleep 1000000))
