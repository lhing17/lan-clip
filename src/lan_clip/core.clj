(ns lan-clip.core
  (:require [clojure.java.io :as jio])
  (:import (java.awt Toolkit Image Color)
           (java.awt.datatransfer DataFlavor ClipboardOwner Clipboard)
           (javax.imageio ImageIO)
           (java.io File)
           (java.awt.image RenderedImage BufferedImage ImageObserver)
           (java.util.concurrent.locks ReentrantLock Condition)))

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

(defn- print-string-on-clipboard [clip]
  (println (.getData clip DataFlavor/stringFlavor)))

(defn- ^ImageObserver image-observer [^ReentrantLock lock ^Condition size? ^Condition data?]
  (proxy [ImageObserver]
         []
    (imageUpdate [_ info-flags _ _ _ _]
      (.lock lock)
      (try
        (cond (not= 0 (bit-and info-flags ImageObserver/ALLBITS))
              (do
                (.signal size?)
                (.signal data?)
                false)

              (not= 0 (bit-and info-flags (bit-or ImageObserver/WIDTH ImageObserver/HEIGHT)))
              (do
                (.signal size?)
                true)

              :else
              true)
        (finally
          (.unlock lock))))))

(defn- ^BufferedImage buffered-image [^Image image]
  (if (instance? BufferedImage image)
    image
    (let [lock (ReentrantLock.)
          size? (.newCondition lock)
          data? (.newCondition lock)
          o (image-observer lock size? data?)
          width (atom (.getWidth image o))
          height (atom (.getHeight image o))]
      (.lock lock)
      (try (while (or (< @width 0) (< @height 0))
             (.awaitUninterruptibly size?)
             (reset! width (.getWidth image o))
             (reset! height (.getHeight image o)))
           (let [bi (BufferedImage. @width @height BufferedImage/TYPE_INT_ARGB)
                 g (.createGraphics bi)]
             (try
               (doto g
                 (.setBackground (Color. 0 true))
                 (.clearRect 0 0 @width @height))
               (while (not (.drawImage g image 0 0 o))
                 (.awaitUninterruptibly data?))
               (finally
                 (.dispose g)))
             bi)
           (finally
             (.unlock lock))))))

(defmulti handle-flavor (fn [^Clipboard clip]
                          (first (filter #(.isDataFlavorAvailable clip %) [DataFlavor/imageFlavor
                                                                           DataFlavor/stringFlavor
                                                                           DataFlavor/javaFileListFlavor]))))

(defmethod handle-flavor DataFlavor/stringFlavor [clip]
  (print-string-on-clipboard clip))

(defmethod handle-flavor DataFlavor/imageFlavor [clip]
  (let [data (.getData clip DataFlavor/imageFlavor)]

    (ImageIO/write ^RenderedImage (buffered-image data) "png" ^File (jio/file (str "D:/" (System/currentTimeMillis) ".png")))))

(defn set-interval [interval callback]
  (future
    (while true
      (try
        (Thread/sleep interval)
        (callback)
        (catch Exception e (.printStackTrace e))))))

(defn -main [& _]
  (set-interval 2000 #(handle-flavor clip))
  (Thread/sleep 1000000))