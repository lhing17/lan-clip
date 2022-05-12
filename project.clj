(defproject lan-clip "1.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [io.netty/netty-all "4.1.76.Final"]
                 [commons-codec "1.15"]
                 [clj-commons/pomegranate "1.2.1"]
                 [seesaw "1.5.0"]
                 [com.formdev/flatlaf "2.2"]
                 [commons-io "2.11.0"]
                 [cljfx "1.7.19"]
                 [nrepl "0.9.0"]]
  :mirrors {"clojars" {:name "ustc"
                       :url "https://mirrors.tuna.tsinghua.edu.cn/clojars"}}

  :prep-tasks [["compile" "lan-clip.socket.content"] "javac" "compile"]
  :jvm-opts ["-server"]
  :main lan-clip.core
  :profiles {:uberjar {:omit-source true
                       :env         {:production true}
                       :aot         :all}
             :reveal  {:dependencies [[vlaaad/reveal "1.3.273"]]
                       :repl-options {:nrepl-middleware [vlaaad.reveal.nrepl/middleware]}}}
  :pedantic? false

  :repl-options {:init-ns lan-clip.core})


(comment
  (use '[cemerick.pomegranate :only (add-dependencies)])
  (add-dependencies :coordinates '[[seesaw "1.5.0"]
                                   [com.formdev/flatlaf "2.0.1"]
                                   [commons-io "2.11.0"]
                                   [cljfx "1.7.19"]]
                    :repositories (merge cemerick.pomegranate.aether/maven-central
                        {"clojars" "https://clojars.org/repo"}
                        ;;{"clojars" "https://mirrors.tuna.tsinghua.edu.cn/clojars"}
                        ))
  ,)
