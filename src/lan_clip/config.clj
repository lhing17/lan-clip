(ns lan-clip.config
  "lan-clip 配置中心：集中默认配置、读取、合并与校验。

  设计目标：
  - 让 core.clj / socket/server.clj 不再各自 inline 写默认值。
  - 让测试可以传入显式 path，避免污染用户 home 目录。
  - 文件不存在不抛异常，回退到默认配置，便于初次启动。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio])
  (:import (java.io PushbackReader)))

(def default-config
  "lan-clip 默认配置，是其它来源（用户配置、命令行）合并的基线。
  注意 :target-host 必须保持 localhost，避免无意中向真实局域网 IP 发送。"
  {:port 9002
   :target-host "localhost"
   :target-port 9002
   :file-size 2048
   :interval 2000
   :secret-key "lan-clip"
   :max-frame-size 10485760
   :received-files-dir nil
   :log-file nil
   :device-name ""})

(defn- read-edn-file
  "从指定路径读取 EDN 文件；文件不存在或 path 为 nil 时返回 nil。"
  [path]
  (when path
    (let [f (jio/file path)]
      (when (.exists f)
        (with-open [in (-> f jio/reader (PushbackReader.))]
          (edn/read in))))))

(defn node-id-path
  "返回 node-id 持久化文件路径，默认位于 ~/.lan-clip/node-id。"
  []
  (let [home (System/getProperty "user.home")]
    (.getAbsolutePath (jio/file home ".lan-clip" "node-id"))))

(defn default-received-files-dir
  "返回默认接收文件目录的绝对路径，位于 ~/.lan-clip/received-files。"
  []
  (let [home (System/getProperty "user.home")]
    (.getAbsolutePath (jio/file home ".lan-clip" "received-files"))))

(defn default-log-file
  "返回默认日志文件的绝对路径，位于 ~/.lan-clip/lan-clip.log。"
  []
  (let [home (System/getProperty "user.home")]
    (.getAbsolutePath (jio/file home ".lan-clip" "lan-clip.log"))))

(defn- load-or-create-node-id
  "读取或生成并持久化 node-id UUID。"
  []
  (let [p (jio/file (node-id-path))]
    (if (.exists p)
      (edn/read-string (slurp p))
      (let [id (java.util.UUID/randomUUID)]
        (.mkdirs (.getParentFile p))
        (spit p (pr-str id))
        id))))

(defn load-config
  "从 path 加载配置并与默认值合并；文件不存在直接返回 default-config。
  如果配置中不含 :node-id，自动生成并持久化到 ~/.lan-clip/node-id。
  如果 :received-files-dir 为 nil，使用默认目录。"
  [path]
  (let [cfg (merge default-config (or (read-edn-file path) {}))
        cfg (if (:received-files-dir cfg)
              cfg
              (assoc cfg :received-files-dir (default-received-files-dir)))
        cfg (if (:log-file cfg)
              cfg
              (assoc cfg :log-file (default-log-file)))]
    (if (:node-id cfg)
      cfg
      (assoc cfg :node-id (load-or-create-node-id)))))

(defn- valid-port?
  [p]
  (and (integer? p) (<= 1 p 65535)))

(def restart-required-keys
  "修改这些配置后必须重启应用才能生效。"
  #{:port :secret-key :max-frame-size :node-id})

(def hot-reloadable-keys
  "修改这些配置后可热更新，无需重启。"
  #{:target-host :target-port :interval :file-size :received-files-dir :device-name})

(defn save-config!
  "将配置 map 保存为指定路径的 EDN 文件。父目录不存在时自动创建。"
  [path m]
  (let [f (jio/file path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f (pr-str m))))

(defn validate-config
  "校验配置：通过则原样返回 m，否则抛 ex-info。当前仅校验端口范围。"
  [m]
  (when-not (valid-port? (:port m))
    (throw (ex-info "Invalid :port" {:cause :invalid-port :port (:port m)})))
  (when-not (valid-port? (:target-port m))
    (throw (ex-info "Invalid :target-port" {:cause :invalid-target-port
                                            :target-port (:target-port m)})))
  m)
