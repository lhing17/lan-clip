(ns lan-clip.socket.content
  (:import (java.io Serializable)))

(deftype Content [type content]
  Serializable)
