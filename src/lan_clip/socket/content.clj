(ns lan-clip.socket.content
  (:import (java.io Serializable)))

(deftype Content [type content]
  Serializable
  Object
  (toString [this]
    (str "type=" type ",content=" content)))
