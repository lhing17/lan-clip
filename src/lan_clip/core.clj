(ns lan-clip.core
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor ClipboardOwner)))

(def clip (.getSystemClipboard (Toolkit/getDefaultToolkit)))

(defn- set-owner [clpbd owner]
  (.setContents clpbd (.getContents clpbd nil) owner))


(defrecord Owner
  []

  ClipboardOwner
  (lostOwnership [this clipboard _]
    (Thread/sleep 1000)
    (when (.isDataFlavorAvailable clipboard DataFlavor/stringFlavor)
      (println (.getData clipboard DataFlavor/stringFlavor))
      (set-owner clipboard this))))


(defn -main [& _]
  (set-owner clip (->Owner))
  (Thread/sleep 1000000))