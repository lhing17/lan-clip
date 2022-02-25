(ns lan-clip.util
  (:require [clojure.java.io :as jio])
  (:import (org.apache.commons.codec.digest DigestUtils)
           (java.awt Image Color)
           (java.awt.image BufferedImage ImageObserver)
           (java.util.concurrent.locks ReentrantLock Condition)
           (java.io ByteArrayOutputStream File InputStream)
           (javax.imageio ImageIO)
           (clojure.lang Seqable)))

(defn set-interval [interval callback]
  (future
    (while true
      (try
        (Thread/sleep interval)
        (callback)
        (catch Exception e (.printStackTrace e))))))

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

(defn ^BufferedImage buffered-image [^Image image]
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

(defn image->bytes [^BufferedImage buf-img]
  (let [bos (ByteArrayOutputStream.)]
    (ImageIO/write buf-img "jpg" bos)
    (.toByteArray bos)))

(defprotocol Digestable
  (md5 [this]))

(extend-protocol Digestable
  (class (byte-array 0))
  (md5 [this] (DigestUtils/md5Hex this))

  String
  (md5 [this] (DigestUtils/md5Hex this))

  InputStream
  (md5 [this] (DigestUtils/md5Hex this))

  BufferedImage
  (md5 [this] (DigestUtils/md5Hex (image->bytes this)))

  Image
  (md5 [this] (md5 (buffered-image this)))

  File
  (md5 [this] (md5 (jio/input-stream this)))

  Seqable
  (md5 [this] (md5 (str (mapv md5 (seq this)))))

  Object
  (md5 [this] (md5 (str this))))
