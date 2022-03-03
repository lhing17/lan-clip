(defproject lan-clip "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [io.netty/netty-all "4.1.74.Final"]
                 [commons-codec "1.15"]
                 [clj-commons/pomegranate "1.2.0"]]

  :prep-tasks [["compile" "lan-clip.socket.content"] "javac" "compile"]
  :jvm-opts ["-server"]
  :main lan-clip.core
  :profiles {:uberjar {:omit-source true
                       :env {:production true}
                       :aot :all}
             :reveal {:dependencies  [[vlaaad/reveal "1.3.269"]]
                      :repl-options {:nrepl-middleware [vlaaad.reveal.nrepl/middleware]}}}
  :pedantic? false

  :repl-options {:init-ns lan-clip.core})
