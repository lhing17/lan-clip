(ns lan-clip.socket.content
  (:import (java.io Serializable)))

(deftype Content [type content]
  Serializable)

;(gen-class
;  :name lan_clip.socket.content.Content
;  :implements [java.io.Serializable]
;  :state "state"
;  :init "init"
;  :prefix "-"
;  :constructors {[Class Object] []}
;  :methods [[setType [Class] void]
;            [getType [] Class]
;            [setContent [Object] void]
;            [getContent [] Object]])
;
;(defn -init [type content]
;  [[] (atom {:type type :content content})])
;
;(defn setfield
;  [this key value]
;  (swap! (.state this) into {key value}))
;
;(defn getfield
;  [this key]
;  (@(.state this) key))
;
;(defn -getType
;  [this]
;  (getfield this :type))
;
;(defn -setType [this type]
;  (setfield this :type type))
;
;(defn getContent
;  [this]
;  (getfield this :content))
;
;(defn -setContent [this content]
;  (setfield this :content content))
