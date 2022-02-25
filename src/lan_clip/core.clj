(ns lan-clip.core
  (:require [clojure.java.io :as jio])
  (:import (java.awt Toolkit Image)
           (java.awt.datatransfer DataFlavor ClipboardOwner Clipboard)
           (javax.imageio ImageIO)
           (java.io File)
           (java.awt.image RenderedImage BufferedImage)))

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

(defn- ^BufferedImage buffered-image [^Image image]
  (if (instance? BufferedImage image)
    image
    ))

;public static BufferedImage getImage(Image image) {
;                                                   if(image instanceof BufferedImage) return (BufferedImage)image;
;                                                     Lock lock = new ReentrantLock();
;                                                     Condition size = lock.newCondition(), data = lock.newCondition();
;                                                   ImageObserver o = (img, infoflags, x, y, width, height) -> {
;         lock.lock();
;         try {
;              if((infoflags&ImageObserver.ALLBITS)!=0) {
;                                                        size.signal();
;                                                        data.signal();
;                                                        return false;
;                                                        }
;                if((infoflags&(ImageObserver.WIDTH|ImageObserver.HEIGHT))!=0)
;                size.signal();
;                return true;
;              }
;         finally { lock.unlock(); }
;                  };(do
;         BufferedImage bi;
;         lock.lock();
;         try {
;              int width, height=0;
;                  while( (width=image.getWidth(o))<0 || (height=image.getHeight(o))<0 )
;                  size.awaitUninterruptibly();
;                  bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
;              Graphics2D g = bi.createGraphics();
;              try {
;                   g.setBackground(new Color(0, true));
;                   g.clearRect(0, 0, width, height);
;                   while(!g.drawImage(image, 0, 0, o)) data.awaitUninterruptibly();
;                   } finally { g.dispose(); }
;                              } finally { lock.unlock(); }
;                                         return bi;
;                                         }

(defmulti handle-flavor (fn [^Clipboard clip]
                          (first (filter #(.isDataFlavorAvailable clip %) [DataFlavor/imageFlavor
                                                                           DataFlavor/stringFlavor
                                                                           DataFlavor/javaFileListFlavor]))))

(defmethod handle-flavor DataFlavor/stringFlavor [clip]
  (print-string-on-clipboard clip))

(defmethod handle-flavor DataFlavor/imageFlavor [clip]
  (let [data (.getData clip DataFlavor/imageFlavor)]

    (ImageIO/write ^RenderedImage data "png" ^File (jio/file (str "/Users/lianghao/Documents/" (System/currentTimeMillis) "/.png")))))

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