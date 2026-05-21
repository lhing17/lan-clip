(ns lan-clip.discovery
  "局域网设备发现与配对：基于 UDP 广播的 beacon 机制 + 定向配对消息。
  每个节点周期性向 255.255.255.255 发送 beacon，其他节点监听并维护 peer 列表。
  配对通过定向 UDP 消息完成：请求方发送 pair-request，接收方回复 pair-response。"
  (:require [clojure.edn :as edn])
  (:import (java.net DatagramSocket DatagramPacket InetAddress)
           (java.time Instant Duration)
           (java.util.concurrent.atomic AtomicBoolean)))

(def discovery-port 9616)
(def ^:private beacon-interval-ms 3000)
(def ^:private peer-ttl-seconds 30)
(def ^:private beacon-max-bytes 1024)

(defn- broadcast-address []
  (InetAddress/getByName "255.255.255.255"))

(defn- now []
  (Instant/now))

(defn- expired? [^Instant last-seen]
  (let [cutoff (.minusSeconds (Instant/now) peer-ttl-seconds)]
    (.isBefore last-seen cutoff)))

(defn create-registry
  "创建一个空的 peer 注册表 atom。"
  []
  (atom {}))

(defn register-peer!
  "将收到的 beacon 解析为 peer 信息并注册到 registry。"
  [registry sender-host beacon-edn]
  (let [peer {:node-id (:node-id beacon-edn)
              :device-name (:device-name beacon-edn "")
              :host sender-host
              :port (:port beacon-edn)
              :version (:version beacon-edn 1)
              :last-seen (now)}]
    (when (:node-id peer)
      (swap! registry assoc (:node-id peer) peer))))

(defn recent-peers
  "返回 registry 中未过期的 peer 列表（按 device-name 排序），不含本节点。"
  ([registry]
   (recent-peers registry nil))
  ([registry self-node-id]
   (->> (vals @registry)
        (remove #(expired? (:last-seen %)))
        (remove #(= self-node-id (:node-id %)))
        (sort-by :device-name)
        (vec))))

(defn- send-beacon!
  "向广播地址发送一次 beacon。"
  [^DatagramSocket socket node-id device-name port]
  (let [payload (pr-str {:node-id node-id
                         :device-name device-name
                         :port port
                         :version 1})
        bytes (.getBytes payload "UTF-8")
        packet (DatagramPacket. bytes (alength bytes)
                                (broadcast-address) discovery-port)]
    (.send socket packet)))

(defn- send-udp!
  "向指定主机发送 UDP 消息。"
  [^DatagramSocket socket host port msg-map]
  (let [payload (pr-str msg-map)
        bytes (.getBytes payload "UTF-8")
        addr (InetAddress/getByName host)
        packet (DatagramPacket. bytes (alength bytes) addr port)]
    (.send socket packet)))

(defn- receive-udp!
  "从 socket 接收一个 UDP 消息，返回 [sender-host msg-map]；超时或解析失败返回 nil。"
  [^DatagramSocket socket]
  (let [buf (byte-array beacon-max-bytes)
        packet (DatagramPacket. buf (alength buf))]
    (try
      (.setSoTimeout socket 1000)
      (.receive socket packet)
      (let [data (String. buf 0 (.getLength packet) "UTF-8")
            sender (.getHostAddress (.getAddress packet))]
        [sender (edn/read-string data)])
      (catch Exception _
        nil))))

(defn handle-incoming-msg
  "处理收到的 UDP 消息：beacon 注册到 registry，pair-request 调用 on-pair-request 回调，
  pair-response 调用 on-pair-response 回调。"
  [registry sender-host msg on-pair-request on-pair-response]
  (case (:msg-type msg)
    :pair-request
    (when on-pair-request
      (on-pair-request sender-host msg))

    :pair-response
    (when on-pair-response
      (on-pair-response sender-host msg))

    ;; 默认当作 beacon（兼容旧格式不含 :msg-type 的情况）
    (register-peer! registry sender-host msg)))

(defn send-pair-request!
  "向指定 peer 发送配对请求。"
  [socket host port self-node-id device-name listen-port secret-key]
  (send-udp! socket host port
             {:msg-type :pair-request
              :node-id self-node-id
              :device-name device-name
              :port listen-port
              :secret-key secret-key}))

(defn send-pair-response!
  "向指定主机发送配对响应。"
  [socket host port self-node-id device-name listen-port status]
  (send-udp! socket host port
             {:msg-type :pair-response
              :node-id self-node-id
              :device-name device-name
              :port listen-port
              :status status}))

(defn start-discovery
  "启动设备发现服务。
  - on-pair-request: 收到配对请求时的回调 (fn [sender-host msg]) -> nil
  - on-pair-response: 收到配对响应时的回调 (fn [sender-host msg]) -> nil
  返回一个包含 :stop! 函数的 map，调用后可终止发送与接收线程。"
  [node-id device-name port registry & {:keys [on-pair-request on-pair-response]}]
  (let [running (AtomicBoolean. true)
        sender-socket (doto (DatagramSocket.)
                        (.setBroadcast true))
        receiver-socket (DatagramSocket. discovery-port)
        ;; 发送线程：周期性广播 beacon
        sender-thread (doto (Thread.
                              (fn []
                                (while (.get running)
                                  (try
                                    (send-beacon! sender-socket node-id device-name port)
                                    (catch Exception e
                                      (println "discovery-send-error:" (.getMessage e))))
                                  (Thread/sleep beacon-interval-ms))))
                        (.setDaemon true)
                        (.start))
        ;; 接收线程：监听 beacon 与配对消息
        receiver-thread (doto (Thread.
                                (fn []
                                  (while (.get running)
                                    (when-let [[host msg] (receive-udp! receiver-socket)]
                                      (try
                                        (handle-incoming-msg registry host msg
                                                             on-pair-request on-pair-response)
                                        (catch Exception e
                                          (println "discovery-handle-error:" (.getMessage e))))))))
                          (.setDaemon true)
                          (.start))]
    {:stop! (fn []
              (.set running false)
              (.close sender-socket)
              (.close receiver-socket)
              (.join sender-thread 2000)
              (.join receiver-thread 2000))
     :registry registry
     :sender-socket sender-socket}))
