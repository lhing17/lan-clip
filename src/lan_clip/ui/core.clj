(ns lan-clip.ui.core
  (:require [seesaw.core :refer :all]
            [seesaw.mig :as mig]
            [clojure.string :as str])
  (:import java.awt.Toolkit
           (com.formdev.flatlaf FlatLightLaf)))

(def fr (delay (-> (frame :title "HI!"
                          :content "I'm a label!"
                          :width 400
                          :height 300))))

(def init-panel-items [["实体类名" "gap 10"]
                       [(text :columns 10 :class :bean) "span, growx, wrap"]
                       ["生成文件" "gap 10"]
                       [(grid-panel :columns 5
                                    :items (map #(checkbox :text % :selected? true :class :file-type :id (keyword (str/lower-case %)))
                                                ["Controller" "Service" "Service-Impl" "Mapper" "Xml"]))
                        "span, wrap"]
                       ["返回值" "gap 10"]
                       [(text :columns 20 :class :return-type) ""]
                       ["方法名" "gap 10"]
                       [(text :columns 20 :class :method-name) "wrap"]
                       ["方法参数" "split, span, gaptop 10"]
                       [:separator "growx, wrap, gaptop 10"]
                       [(button :text "生成" :mnemonic \N :listen [:action (fn [])]) "gaptop 10"]])

(def panel-items (atom init-panel-items))

(defn frame-content []
  (mig/mig-panel :id :panel
                 :constraints ["", "[right]"]
                 :items init-panel-items))

(defn- locate-to-center! [frame]
  (let [screen-size (.. Toolkit getDefaultToolkit getScreenSize)
        screen-width (.width screen-size)
        screen-height (.height screen-size)
        frame-width (width frame)
        frame-height (height frame)]
    (move! frame :to [(/ (- screen-width frame-width) 2) (/ (- screen-height frame-height) 2)])
    frame))

(defn -main [& args]
  (FlatLightLaf/setup)
  (invoke-later
    (-> @fr (config! :content (frame-content)) pack! locate-to-center! show!)
    ))



(comment
  (-main)
  (:a {:a 1})
  frame-content,)
