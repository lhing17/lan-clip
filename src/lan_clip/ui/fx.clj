(ns lan-clip.ui.fx
  (:require [cljfx.api :as fx ])
  (:import (javafx.util StringConverter)))

(fx/on-fx-thread
 (fx/create-component
  {:fx/type :stage
   :showing true
   :title "Cljfx example"
   :width 300
   :height 100
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :alignment :center
                  :children [{:fx/type :label
                              :text "Hello world"}]}}}))

(fx/on-fx-thread
 (fx/create-component
  {:fx/type :stage
   :showing true
   :title "ChoiceApp"
   :height 100
   :scene {:fx/type :scene
           :root {:fx/type :h-box
                  :alignment :center
                  :spacing 10
                  :padding 40
                  :children [{:fx/type :label
                              :text "Asset Class:"}
                             {:fx/type :choice-box
                              :pref-width 200
                              :items [["A" "2000"]
                                      ["B" "2001"]]
                              :converter (proxy [StringConverter] []
                                           (toString [p] (first p))
                                           (fromString [s] nil))}]}}}))

(def *state (atom {:title "App title"}))

(defn title-input [{:keys [title]}]
  {:fx/type :text-field
   :on-text-changed #(swap! *state assoc :title %)
   :text title})

(defn root [{:keys [title]}]
  {:fx/type :stage
   :showing true
   :title title
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type :label
                              :text "Window title input"}
                             {:fx/type title-input
                              :title title}]}}})

(def renderer
  (fx/create-renderer
   :middleware (fx/wrap-map-desc assoc :fx/type root)))

(fx/mount-renderer *state renderer)

(comment
  (renderer)
  ,)
