(ns lan-clip.ui.fx
  (:require [cljfx.api :as fx ]
            [cljfx.css :as css])
  (:import (javafx.util StringConverter)))

(def *state (atom {:gravity 10 :friction 0.4}))

(defmulti event-handler :event/type)

(defn root-view [{{:keys [gravity friction]} :state}]
  {:fx/type :stage
   :showing true
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :spacing 20
                  :children [{:fx/type chart-view
                              :gravity gravity
                              :friction friction}
                             {:fx/type :h-box
                               :spacing 10
                               :children [{:fx/type slider-view
                                           :min 0
                                           :max 5
                                           :value gravity
                                           :label "Gravity"
                                           :event ::set-gravity}
                                          {:fx/type slider-view
                                           :min 0
                                           :max 1
                                           :value friction
                                           :label "Friction"
                                           :event ::set-friction}]}]}}})

(def renderer (fx/create-renderer
               :middleware (fx/wrap-map-desc (fn [state]
                                               {:fx/type root-view
                                                :state state}))
               :opts {:fx.opt/map-event-handler event-handler}))

(fx/mount-renderer *state renderer)

(defn slider-view [{:keys [min max value label event]}]
  {:fx/type :v-box
   :children [{:fx/type :label
               :text label}
              {:fx/type :slider
               :min min
               :max max
               :value value
               :on-value-changed {:event/type event}
               :major-tick-unit max
               :show-tick-labels true}]})

(defn simulate-step [{:keys [velocity y]} gravity friction]
  (let [new-velocity (* (- velocity gravity) (- 1 friction))
        new-y (+ y new-velocity)]
    (if (neg? new-y)
      {:velocity (- new-velocity) :y 0}
      {:velocity new-velocity :y new-y})))

(defn chart-view [{:keys [gravity friction]}]
  {:fx/type :line-chart
   :x-axis {:fx/type :number-axis
            :label "Time"}
   :y-axis {:fx/type :number-axis
            :label "Y"}
   :data [{:fx/type :xy-chart-series
           :name "Position by time"
           :data (->> {:velocity 0 :y 100}
                      (iterate #(simulate-step % gravity friction))
                      (take 100)
                      (map-indexed (fn [index {:keys [y]}]
                                     {:fx/type :xy-chart-data
                                      :x-value index
                                      :y-value y})))}]})
 
(defmethod event-handler ::set-friction [e]
  (swap! *state assoc :friction (:fx/event e)))

(defmethod event-handler ::set-gravity [e]
  (swap! *state assoc :gravity (:fx/event e)))





(comment
  (renderer)
  (println (slurp (::css/url style)))
  (swap! *state assoc :gravity 1)
  (fx/create-component)
  ,)
