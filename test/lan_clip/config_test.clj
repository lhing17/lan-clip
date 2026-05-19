(ns lan-clip.config-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [lan-clip.config :as config])
  (:import (java.io File)
           (java.util UUID)))

(defn- temp-edn
  "在系统临时目录创建一个一次性的 EDN 文件，写入给定字符串后返回 File。"
  ^File [^String content]
  (let [f (File/createTempFile "lan-clip-config-test" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest default-config-has-required-keys
  (testing "default-config 应包含 :port :target-host :target-port :file-size :interval :secret-key :max-frame-size"
    (let [d config/default-config]
      (is (map? d))
      (is (contains? d :port))
      (is (contains? d :target-host))
      (is (contains? d :target-port))
      (is (contains? d :file-size))
      (is (contains? d :interval))
      (is (contains? d :secret-key))
      (is (contains? d :max-frame-size)))))

(deftest default-config-has-safe-default-host
  (testing "默认 target-host 必须是 localhost，避免无意中向局域网真实 IP 发送"
    (is (= "localhost" (:target-host config/default-config)))))

(deftest load-config-missing-file-falls-back-to-defaults
  (testing "传入不存在的路径时，load-config 应返回默认配置并自动生成 node-id"
    (let [missing (jio/file (System/getProperty "java.io.tmpdir")
                            (str "lan-clip-config-missing-" (System/nanoTime) ".edn"))
          temp-node-id-file (File/createTempFile "node-id" "")
          _ (.delete temp-node-id-file)]
      (with-redefs [config/node-id-path (constantly (.getAbsolutePath temp-node-id-file))]
        (is (false? (.exists missing)))
        (let [loaded (config/load-config (.getAbsolutePath missing))]
          (is (= config/default-config (dissoc loaded :node-id)))
          (is (instance? UUID (:node-id loaded))))))))

(deftest load-config-custom-overrides-merge-on-top-of-defaults
  (testing "用户提供的配置应覆盖默认值，其余键保留默认"
    (let [f (temp-edn "{:port 19002 :file-size 1024}")
          loaded (config/load-config (.getAbsolutePath f))]
      (is (= 19002 (:port loaded)))
      (is (= 1024 (:file-size loaded)))
      (is (= (:target-host config/default-config) (:target-host loaded)))
      (is (= (:target-port config/default-config) (:target-port loaded)))
      (is (= (:interval config/default-config) (:interval loaded))))))

(deftest validate-config-rejects-out-of-range-port
  (testing "validate-config 应拒绝越界端口（<=0 或 >65535），返回 ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config (assoc config/default-config :port 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config (assoc config/default-config :port 70000))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config (assoc config/default-config :target-port -1))))))

(deftest validate-config-accepts-default-config
  (testing "默认配置本身必须通过校验"
    (is (= config/default-config (config/validate-config config/default-config)))))

(deftest load-config-generates-node-id-when-missing
  (testing "当 node-id 文件不存在时，load-config 应生成并持久化新的 UUID"
    (let [temp-dir (File/createTempFile "lan-clip-node-id" "")
          _ (.delete temp-dir)
          temp-node-id-file (File. temp-dir "node-id")]
      (with-redefs [config/node-id-path (constantly (.getAbsolutePath temp-node-id-file))]
        (let [cfg (config/load-config nil)]
          (is (instance? UUID (:node-id cfg)) "应生成 UUID")
          (is (.exists temp-node-id-file) "node-id 文件应被创建")
          (let [stored-id (edn/read-string (slurp temp-node-id-file))]
            (is (= (:node-id cfg) stored-id) "配置中的 node-id 应与文件内容一致")))))))

(deftest load-config-reuses-existing-node-id
  (testing "当 node-id 文件已存在时，load-config 应复用已有 UUID"
    (let [temp-dir (File/createTempFile "lan-clip-node-id" "")
          _ (.delete temp-dir)
          temp-node-id-file (File. temp-dir "node-id")
          existing-id (UUID/randomUUID)]
      (.mkdirs temp-dir)
      (spit temp-node-id-file (pr-str existing-id))
      (with-redefs [config/node-id-path (constantly (.getAbsolutePath temp-node-id-file))]
        (let [cfg (config/load-config nil)]
          (is (= existing-id (:node-id cfg)) "应复用已有的 node-id"))))))

(deftest load-config-user-node-id-takes-precedence
  (testing "用户配置中显式指定的 node-id 应优先于自动生成的"
    (let [temp-dir (File/createTempFile "lan-clip-node-id" "")
          _ (.delete temp-dir)
          temp-node-id-file (File. temp-dir "node-id")
          user-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
          f (temp-edn (str "{:node-id #uuid \"" user-id "\"}"))]
      (with-redefs [config/node-id-path (constantly (.getAbsolutePath temp-node-id-file))]
        (let [cfg (config/load-config (.getAbsolutePath f))]
          (is (= user-id (:node-id cfg)) "用户配置的 node-id 应优先"))))))
